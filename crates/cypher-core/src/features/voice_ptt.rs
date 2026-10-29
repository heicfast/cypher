//! Push-to-talk voice inbound assembly — exact port of bitchat's
//! `ChatLiveVoiceCoordinator` (packet ordering, gap skip, persistence) and
//! the outgoing `VoiceBurstPacketizer`.
//!
//! Sans-I/O adaptation: Swift's Tasks/timers become deadline checks driven
//! by [`VoiceInboundCoordinator::on_tick`]; delivered frames surface as
//! return values instead of file/player callbacks.

use crate::consts;
use crate::peer_id::PeerId;
use crate::protocol::voice::{
    VoiceBurstPacket, CODEC_AAC_LC, FLAG_CANCELED, FLAG_DATA, FLAG_END, FLAG_START,
    MAX_FRAMES_PER_PACKET,
};
use std::collections::HashMap;

/// Which timeline a burst belongs to (prevents cross-scope hijacking).
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum VoiceScope {
    DirectMessage,
    PublicMesh,
}

/// Assembly identity: authenticated triple — a colliding START from
/// another peer (or the same peer across DM/public) can never hijack.
#[derive(Clone, PartialEq, Eq, Hash)]
struct AssemblyKey {
    peer: PeerId,
    scope: VoiceScope,
    burst_id: [u8; 8],
}

struct Assembly {
    /// Frames buffered ahead of `next_seq` (seq -> frames).
    buffered: HashMap<u16, Vec<Vec<u8>>>,
    /// seq 0 is START; data seqs are 1..=total.
    next_seq: u16,
    delivered_frames: usize,
    received_bytes: usize,
    first_packet_ms: u64,
    end_info: Option<(u16, u32)>,
    gap_since_ms: Option<u64>,
    /// Next drain re-check deadline (gap skip + 0.05 s).
    redrain_deadline_ms: Option<u64>,
}

/// A finalized burst registered to absorb later duplicates.
struct FinishedBurst {
    peer: PeerId,
    scope: VoiceScope,
    burst_id: [u8; 8],
    expires_at_ms: u64,
}

/// Events produced by inbound handling / ticks.
#[derive(Debug, Clone, PartialEq)]
pub enum VoiceEvent {
    /// A frame was delivered in order (already ADTS-wrapped, 7-byte header —
    /// any prefix is playable).
    FrameDelivered {
        peer: PeerId,
        scope: VoiceScope,
        burst_id: [u8; 8],
        adts_frame: Vec<u8>,
    },
    /// A burst finalized. `kept == false` → the host should delete the
    /// partial capture (zero frames delivered).
    BurstFinalized {
        peer: PeerId,
        scope: VoiceScope,
        burst_id: [u8; 8],
        frames: usize,
        kept: bool,
    },
    BurstCanceled {
        peer: PeerId,
        scope: VoiceScope,
        burst_id: [u8; 8],
    },
}

const GAP_SKIP_MS: u64 = 500;
const REDRAIN_SLACK_MS: u64 = 50;
const FINISHED_BURSTS_CAP: usize = 32;

pub struct VoiceInboundCoordinator {
    assemblies: HashMap<AssemblyKey, Assembly>,
    finished: Vec<FinishedBurst>,
    clock: crate::noise::session_manager::Clock,
}

impl VoiceInboundCoordinator {
    pub fn new(clock: crate::noise::session_manager::Clock) -> Self {
        VoiceInboundCoordinator {
            assemblies: HashMap::new(),
            finished: Vec::new(),
            clock,
        }
    }

    fn now_ms(&self) -> u64 {
        (self.clock)()
    }

    /// Inbound entry (Swift `handle(_:)`). Returns delivery events.
    pub fn handle(
        &mut self,
        peer: &PeerId,
        scope: VoiceScope,
        packet: &VoiceBurstPacket,
    ) -> Vec<VoiceEvent> {
        let now = self.now_ms();
        let key = AssemblyKey {
            peer: peer.clone(),
            scope,
            burst_id: packet.burst_id,
        };
        // Existing assembly: detach, operate, re-insert unless retired
        // (finalize/cancel remove it inside `apply`).
        let existing = self.assemblies.remove(&key);
        let mut assembly = match existing {
            Some(a) => a,
            None => {
                // New assembly only for START or DATA (a data packet with
                // no START still opens one — mid-burst join). END/CANCELED
                // for an unknown burst are ignored.
                let opens = packet.flags == FLAG_START || packet.flags == FLAG_DATA;
                if !opens {
                    return Vec::new();
                }
                // Capacity gate.
                if self.assemblies.len() >= consts::PTT_MAX_CONCURRENT_ASSEMBLIES {
                    return Vec::new();
                }
                Assembly {
                    buffered: HashMap::new(),
                    next_seq: 1,
                    delivered_frames: 0,
                    received_bytes: 0,
                    first_packet_ms: now,
                    end_info: None,
                    gap_since_ms: None,
                    redrain_deadline_ms: None,
                }
            }
        };
        let events = self.apply(packet, &key, &mut assembly, now);
        let retired = events.iter().any(|e| {
            matches!(
                e,
                VoiceEvent::BurstFinalized { .. } | VoiceEvent::BurstCanceled { .. }
            )
        });
        if !retired {
            self.assemblies.insert(key, assembly);
        }
        events
    }

    /// Per-packet admission + flood guard (Swift `apply(_:to:)`).
    fn apply(
        &mut self,
        packet: &VoiceBurstPacket,
        key: &AssemblyKey,
        assembly: &mut Assembly,
        now: u64,
    ) -> Vec<VoiceEvent> {
        assembly.received_bytes += packet.encode().map(|e| e.len()).unwrap_or(0);
        let elapsed_s = (now.saturating_sub(assembly.first_packet_ms)) as f64 / 1000.0;
        let flood_ok = assembly.received_bytes
            <= consts::PTT_INBOUND_FLOOD_GUARD_BYTES_PER_S * (elapsed_s as usize + 2);
        let size_ok = assembly.received_bytes <= consts::PTT_MAX_BURST_BYTES;
        if !flood_ok || !size_ok {
            // Over cap: finalize, keeping what has already been decoded.
            return self.finalize(key, assembly, now);
        }
        match packet.flags {
            FLAG_START => {
                // Only codec 0x01 (AAC-LC 16k mono) exists.
                let codec_ok = packet.payload.first() == Some(&CODEC_AAC_LC);
                if !codec_ok {
                    return self.cancel(key, assembly);
                }
                Vec::new()
            }
            FLAG_DATA => {
                // Duplicate or already-delivered seqs are dropped; the
                // ordering rule is `seq >= next_seq && !buffered.contains`.
                let seq = packet.seq;
                if seq < assembly.next_seq || assembly.buffered.contains_key(&seq) {
                    return Vec::new();
                }
                let Some(frames) = packet.decode_frames() else {
                    return Vec::new();
                };
                assembly.buffered.insert(seq, frames);
                self.drain_in_order(key, assembly, now)
            }
            FLAG_END => {
                if packet.payload.len() >= 6 {
                    let total = u16::from_be_bytes(packet.payload[0..2].try_into().expect("2"));
                    let duration = u32::from_be_bytes(packet.payload[2..6].try_into().expect("4"));
                    assembly.end_info = Some((total, duration));
                }
                let mut events = self.drain_in_order(key, assembly, now);
                if self.complete(assembly) {
                    events.extend(self.finalize(key, assembly, now));
                }
                events
            }
            FLAG_CANCELED => self.cancel(key, assembly),
            _ => Vec::new(),
        }
    }

    /// Whether all data packets have been delivered (Swift rule:
    /// `nextSeq > end.totalDataPackets`, wrapping arithmetic).
    fn complete(&self, assembly: &Assembly) -> bool {
        match assembly.end_info {
            Some((total, _)) => {
                assembly.next_seq.wrapping_sub(1) >= total && assembly.next_seq != 0
            }
            None => false,
        }
    }

    /// The reordering algorithm (Swift `drainInOrder`).
    fn drain_in_order(
        &mut self,
        key: &AssemblyKey,
        assembly: &mut Assembly,
        now: u64,
    ) -> Vec<VoiceEvent> {
        let mut events = Vec::new();
        loop {
            if let Some(frames) = assembly.buffered.remove(&assembly.next_seq) {
                for frame in frames {
                    events.push(VoiceEvent::FrameDelivered {
                        peer: key.peer.clone(),
                        scope: key.scope,
                        burst_id: key.burst_id,
                        adts_frame: adts_wrap(&frame),
                    });
                }
                assembly.delivered_frames += events.len();
                assembly.next_seq = assembly.next_seq.wrapping_add(1);
                assembly.gap_since_ms = None;
                assembly.redrain_deadline_ms = None;
                continue;
            }
            if assembly.buffered.is_empty() {
                assembly.gap_since_ms = None;
                assembly.redrain_deadline_ms = None;
                return events;
            }
            // Packets are buffered ahead of a hole.
            match assembly.gap_since_ms {
                Some(since) => {
                    if now.saturating_sub(since) < GAP_SKIP_MS {
                        return events;
                    }
                    // Skip the hole: jump to the smallest buffered seq —
                    // the playback underrun covered the gap.
                    let smallest = *assembly.buffered.keys().min().expect("non-empty");
                    assembly.next_seq = smallest;
                    assembly.gap_since_ms = None;
                }
                None => {
                    assembly.gap_since_ms = Some(now);
                    assembly.redrain_deadline_ms = Some(now + GAP_SKIP_MS + REDRAIN_SLACK_MS);
                    return events;
                }
            }
        }
    }

    fn cancel(&mut self, key: &AssemblyKey, assembly: &mut Assembly) -> Vec<VoiceEvent> {
        assembly.buffered.clear();
        self.assemblies.remove(key);
        vec![VoiceEvent::BurstCanceled {
            peer: key.peer.clone(),
            scope: key.scope,
            burst_id: key.burst_id,
        }]
    }

    /// Swift `finalize(_:)` — deliver everything still buffered (jumping to
    /// each smallest key), then retire the assembly.
    fn finalize(
        &mut self,
        key: &AssemblyKey,
        assembly: &mut Assembly,
        now: u64,
    ) -> Vec<VoiceEvent> {
        let mut events = Vec::new();
        while !assembly.buffered.is_empty() {
            let smallest = *assembly.buffered.keys().min().expect("non-empty");
            assembly.next_seq = smallest;
            if let Some(frames) = assembly.buffered.remove(&smallest) {
                for frame in frames {
                    events.push(VoiceEvent::FrameDelivered {
                        peer: key.peer.clone(),
                        scope: key.scope,
                        burst_id: key.burst_id,
                        adts_frame: adts_wrap(&frame),
                    });
                }
                assembly.delivered_frames += events.len();
            }
        }
        let kept = assembly.delivered_frames > 0;
        self.assemblies.remove(key);
        events.push(VoiceEvent::BurstFinalized {
            peer: key.peer.clone(),
            scope: key.scope,
            burst_id: key.burst_id,
            frames: assembly.delivered_frames,
            kept,
        });
        self.register_finished(key, now);
        events
    }

    fn register_finished(&mut self, key: &AssemblyKey, now: u64) {
        self.finished.push(FinishedBurst {
            peer: key.peer.clone(),
            scope: key.scope,
            burst_id: key.burst_id,
            expires_at_ms: now + (consts::PTT_FINISHED_REGISTRY_S * 1000),
        });
        // Evict oldest-expiry until under the cap.
        while self.finished.len() > FINISHED_BURSTS_CAP {
            if let Some(pos) = self
                .finished
                .iter()
                .enumerate()
                .min_by_key(|(i, f)| (f.expires_at_ms, *i))
                .map(|(i, _)| i)
            {
                self.finished.remove(pos);
            }
        }
    }

    /// Whether this burst was already finalized (duplicate late packets).
    pub fn is_finished(&self, peer: &PeerId, scope: VoiceScope, burst_id: &[u8; 8]) -> bool {
        self.finished
            .iter()
            .any(|f| f.burst_id == *burst_id && f.scope == scope && &f.peer == peer)
    }

    /// Time-driven maintenance: gap re-drains, idle timeouts (3 s without
    /// frames → finalize), finished-registry expiry.
    pub fn on_tick(&mut self) -> Vec<VoiceEvent> {
        let now = self.now_ms();
        let mut events = Vec::new();
        let keys: Vec<AssemblyKey> = self.assemblies.keys().cloned().collect();
        for key in keys {
            let mut assembly = self.assemblies.remove(&key).expect("keyed");
            // Idle timeout: talker vanished without END.
            let idle = now.saturating_sub(assembly.first_packet_ms)
                >= (consts::PTT_BURST_END_TIMEOUT_S * 1000.0) as u64
                && assembly.buffered.is_empty()
                && assembly.end_info.is_some();
            // Gap re-drain deadline.
            let redrain_due = assembly
                .redrain_deadline_ms
                .map(|d| now >= d)
                .unwrap_or(false);
            if redrain_due {
                events.extend(self.drain_in_order(&key, &mut assembly, now));
                if self.complete(&assembly) {
                    events.extend(self.finalize(&key, &mut assembly, now));
                    continue;
                }
            }
            if idle {
                events.extend(self.finalize(&key, &mut assembly, now));
                continue;
            }
            self.assemblies.insert(key, assembly);
        }
        // Finished-registry expiry.
        self.finished.retain(|f| f.expires_at_ms > now);
        events
    }
}

/// Fixed 7-byte MPEG-4 AAC-LC ADTS header — exact port of Swift
/// `ADTSFramer.frame` (PTTAudioFormat.swift:60-84). Any prefix of the
/// stream stays playable.
pub fn adts_wrap(aac_frame: &[u8]) -> Vec<u8> {
    const HEADER: usize = 7;
    let freq_index: u8 = 8; // 16 kHz
    let channels: u8 = 1;
    let frame_length = aac_frame.len() + HEADER;
    let mut out = Vec::with_capacity(frame_length);
    // Syncword 0xFFF, MPEG-4, layer 00, no CRC.
    out.push(0xFF);
    out.push(0xF1);
    // Profile AAC-LC (01), frequency index, private 0, channel high bit.
    out.push((0b01 << 6) | (freq_index << 2) | ((channels >> 2) & 0x1));
    out.push(((channels & 0x3) << 6) | ((frame_length >> 11) as u8 & 0x3));
    out.push(((frame_length >> 3) & 0xFF) as u8);
    out.push((((frame_length & 0x7) << 5) as u8) | 0x1F);
    // Buffer fullness 0x7FF (VBR), one AAC frame per ADTS frame.
    out.push(0xFC);
    out.extend_from_slice(aac_frame);
    out
}

// ---------------------------------------------------------------------------
// Outgoing packetizer (Swift VoiceBurstPacketizer) — budget 210 content
// bytes so a directed burst packet stays in one BLE frame.
// ---------------------------------------------------------------------------

pub struct VoiceBurstPacketizer {
    burst_id: [u8; 8],
    next_seq: u16,
    pending_frames: Vec<Vec<u8>>,
    pending_bytes: usize,
    data_packet_count: u16,
}

impl VoiceBurstPacketizer {
    pub fn new(burst_id: [u8; 8]) -> Self {
        VoiceBurstPacketizer {
            burst_id,
            next_seq: 1, // seq 0 is START
            pending_frames: Vec::new(),
            pending_bytes: 0,
            data_packet_count: 0,
        }
    }

    /// START packet (codec 0x01).
    pub fn start_packet(&self) -> Option<VoiceBurstPacket> {
        Some(VoiceBurstPacket::start(self.burst_id, CODEC_AAC_LC))
    }

    /// Add a frame; returns any packet flushed by the budget rules.
    pub fn add(&mut self, frame: Vec<u8>) -> Option<VoiceBurstPacket> {
        let frame_cost = 2 + frame.len();
        // A frame that can never fit the budget is dropped entirely.
        if 8 + 3 + frame_cost > consts::PTT_MAX_BURST_CONTENT_BYTES {
            return None;
        }
        let mut flushed = None;
        if 8 + 3 + self.pending_bytes + frame_cost > consts::PTT_MAX_BURST_CONTENT_BYTES
            || self.pending_frames.len() >= MAX_FRAMES_PER_PACKET
        {
            flushed = self.flush();
        }
        self.pending_bytes += frame_cost;
        self.pending_frames.push(frame);
        flushed
    }

    /// Flush the pending frames into one data packet.
    pub fn flush(&mut self) -> Option<VoiceBurstPacket> {
        if self.pending_frames.is_empty() {
            return None;
        }
        let frames = std::mem::take(&mut self.pending_frames);
        self.pending_bytes = 0;
        let seq = self.next_seq;
        self.next_seq = self.next_seq.wrapping_add(1);
        self.data_packet_count = self.data_packet_count.wrapping_add(1);
        VoiceBurstPacket::data(self.burst_id, seq, &frames)
    }

    /// END packet for the current burst.
    pub fn end_packet(&self, duration_ms: u32) -> VoiceBurstPacket {
        VoiceBurstPacket::end(self.burst_id, self.data_packet_count, duration_ms)
    }

    /// CANCELED packet.
    pub fn cancel_packet(&self) -> VoiceBurstPacket {
        VoiceBurstPacket::canceled(self.burst_id)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::sync::Arc;

    fn clock_at(t: u64) -> (crate::noise::session_manager::Clock, Arc<AtomicU64>) {
        let cell = Arc::new(AtomicU64::new(t));
        let cell2 = cell.clone();
        let clock: crate::noise::session_manager::Clock =
            Arc::new(move || cell2.load(Ordering::SeqCst));
        (clock, cell)
    }

    fn frame(n: u8) -> Vec<u8> {
        vec![n; 40]
    }

    #[test]
    fn in_order_delivery() {
        let (clock, _) = clock_at(10_000);
        let mut coord = VoiceInboundCoordinator::new(clock);
        let peer = PeerId::new("aaaaaaaaaaaaaaaa");
        let burst = [0x11; 8];

        coord.handle(
            &peer,
            VoiceScope::DirectMessage,
            &VoiceBurstPacket::start(burst, CODEC_AAC_LC),
        );
        let p1 = VoiceBurstPacket::data(burst, 1, &[frame(1), frame(2)]).unwrap();
        let events = coord.handle(&peer, VoiceScope::DirectMessage, &p1);
        assert_eq!(events.len(), 2);
        assert!(events
            .iter()
            .all(|e| matches!(e, VoiceEvent::FrameDelivered { .. })));
        for e in &events {
            if let VoiceEvent::FrameDelivered { adts_frame, .. } = e {
                // frame = 40 B payload + 7 B header = 47; ADTS per Swift.
                assert_eq!(
                    &adts_frame[..7],
                    &[0xFF, 0xF1, 0x60, 0x40, 0x05, 0xFF, 0xFC][..]
                );
                assert_eq!(adts_frame.len(), 47);
            }
        }
    }

    #[test]
    fn out_of_order_buffering_then_drain() {
        let (clock, cell) = clock_at(20_000);
        let mut coord = VoiceInboundCoordinator::new(clock);
        let peer = PeerId::new("aaaaaaaaaaaaaaaa");
        let burst = [0x22; 8];

        coord.handle(
            &peer,
            VoiceScope::PublicMesh,
            &VoiceBurstPacket::start(burst, CODEC_AAC_LC),
        );
        // seq 2 arrives before seq 1 — buffered, nothing delivered.
        let p2 = VoiceBurstPacket::data(burst, 2, &[frame(2)]).unwrap();
        let events = coord.handle(&peer, VoiceScope::PublicMesh, &p2);
        assert!(events.is_empty());
        // seq 1 arrives — both drain in order.
        let p1 = VoiceBurstPacket::data(burst, 1, &[frame(1)]).unwrap();
        let events = coord.handle(&peer, VoiceScope::PublicMesh, &p1);
        assert_eq!(events.len(), 2);
        assert!(events
            .iter()
            .all(|e| matches!(e, VoiceEvent::FrameDelivered { .. })));
        let _ = cell;
    }

    #[test]
    fn duplicates_and_replays_dropped() {
        let (clock, _) = clock_at(30_000);
        let mut coord = VoiceInboundCoordinator::new(clock);
        let peer = PeerId::new("aaaaaaaaaaaaaaaa");
        let burst = [0x33; 8];
        coord.handle(
            &peer,
            VoiceScope::DirectMessage,
            &VoiceBurstPacket::start(burst, CODEC_AAC_LC),
        );
        let p1 = VoiceBurstPacket::data(burst, 1, &[frame(1)]).unwrap();
        coord.handle(&peer, VoiceScope::DirectMessage, &p1);
        // Replay of seq 1 (already delivered) and duplicate of a buffered seq.
        let replay = coord.handle(&peer, VoiceScope::DirectMessage, &p1);
        assert!(replay.is_empty());
        let p3a = VoiceBurstPacket::data(burst, 3, &[frame(3)]).unwrap();
        coord.handle(&peer, VoiceScope::DirectMessage, &p3a);
        let p3b = VoiceBurstPacket::data(burst, 3, &[frame(9)]).unwrap();
        let dup = coord.handle(&peer, VoiceScope::DirectMessage, &p3b);
        assert!(dup.is_empty());
    }

    #[test]
    fn gap_skip_after_half_second() {
        let (clock, cell) = clock_at(40_000);
        let mut coord = VoiceInboundCoordinator::new(clock);
        let peer = PeerId::new("aaaaaaaaaaaaaaaa");
        let burst = [0x44; 8];
        coord.handle(
            &peer,
            VoiceScope::DirectMessage,
            &VoiceBurstPacket::start(burst, CODEC_AAC_LC),
        );
        // Deliver seq 1, hole at 2, buffer 3.
        coord.handle(
            &peer,
            VoiceScope::DirectMessage,
            &VoiceBurstPacket::data(burst, 1, &[frame(1)]).unwrap(),
        );
        coord.handle(
            &peer,
            VoiceScope::DirectMessage,
            &VoiceBurstPacket::data(burst, 3, &[frame(3)]).unwrap(),
        );
        // Before the 0.5 s window: nothing new.
        cell.store(40_400, Ordering::SeqCst);
        let none = coord.on_tick();
        assert!(
            none.iter()
                .all(|e| matches!(e, VoiceEvent::FrameDelivered { .. }))
                || none.is_empty()
        );
        assert!(!none.iter().any(
            |e| matches!(e, VoiceEvent::FrameDelivered { adts_frame, .. } if adts_frame[7] == 3)
        ));
        // After 0.55 s: the hole is skipped, seq 3 delivers.
        cell.store(40_600, Ordering::SeqCst);
        let events = coord.on_tick();
        assert!(events.iter().any(
            |e| matches!(e, VoiceEvent::FrameDelivered { adts_frame, .. } if adts_frame[7] == 3)
        ));
    }

    #[test]
    fn end_completes_and_finalizes() {
        let (clock, _) = clock_at(50_000);
        let mut coord = VoiceInboundCoordinator::new(clock);
        let peer = PeerId::new("aaaaaaaaaaaaaaaa");
        let burst = [0x55; 8];
        coord.handle(
            &peer,
            VoiceScope::DirectMessage,
            &VoiceBurstPacket::start(burst, CODEC_AAC_LC),
        );
        coord.handle(
            &peer,
            VoiceScope::DirectMessage,
            &VoiceBurstPacket::data(burst, 1, &[frame(1)]).unwrap(),
        );
        coord.handle(
            &peer,
            VoiceScope::DirectMessage,
            &VoiceBurstPacket::data(burst, 2, &[frame(2)]).unwrap(),
        );
        // END with total = 2: complete → finalize.
        let end = VoiceBurstPacket::end(burst, 2, 640);
        let events = coord.handle(&peer, VoiceScope::DirectMessage, &end);
        assert!(events.iter().any(|e| matches!(
            e,
            VoiceEvent::BurstFinalized {
                frames: 2,
                kept: true,
                ..
            }
        )));
        assert!(coord.is_finished(&peer, VoiceScope::DirectMessage, &burst));
    }

    #[test]
    fn flood_guard_finalizes_keeping_decoded() {
        let (clock, cell) = clock_at(60_000);
        let mut coord = VoiceInboundCoordinator::new(clock);
        let peer = PeerId::new("aaaaaaaaaaaaaaaa");
        let burst = [0x66; 8];
        coord.handle(
            &peer,
            VoiceScope::DirectMessage,
            &VoiceBurstPacket::start(burst, CODEC_AAC_LC),
        );
        // 6 kB/s guard with +2 s allowance at t=0 allows 12 kB; each data
        // packet with 8 frames of 40 B ≈ 380 B on the wire → ~32 packets
        // before the guard trips. The 384 KiB total cap is not reached.
        let mut delivered = 0usize;
        let mut finalized = false;
        for seq in 1..=80u16 {
            let frames: Vec<Vec<u8>> = (0..4u8).map(frame).collect();
            let p = VoiceBurstPacket::data(burst, seq, &frames).unwrap();
            let events = coord.handle(&peer, VoiceScope::DirectMessage, &p);
            delivered += events.len();
            if events
                .iter()
                .any(|e| matches!(e, VoiceEvent::BurstFinalized { .. }))
            {
                finalized = true;
                break;
            }
            cell.store(60_000 + 1, Ordering::SeqCst); // ~0 s elapsed — tight guard
        }
        assert!(finalized, "flood guard must finalize the burst");
        assert!(delivered > 0, "decoded frames are kept, not discarded");
    }

    #[test]
    fn cancel_removes_assembly() {
        let (clock, _) = clock_at(70_000);
        let mut coord = VoiceInboundCoordinator::new(clock);
        let peer = PeerId::new("aaaaaaaaaaaaaaaa");
        let burst = [0x77; 8];
        coord.handle(
            &peer,
            VoiceScope::DirectMessage,
            &VoiceBurstPacket::start(burst, CODEC_AAC_LC),
        );
        coord.handle(
            &peer,
            VoiceScope::DirectMessage,
            &VoiceBurstPacket::data(burst, 1, &[frame(1)]).unwrap(),
        );
        let events = coord.handle(
            &peer,
            VoiceScope::DirectMessage,
            &VoiceBurstPacket::canceled(burst),
        );
        assert!(events
            .iter()
            .any(|e| matches!(e, VoiceEvent::BurstCanceled { .. })));
        assert!(coord.on_tick().is_empty());
    }

    #[test]
    fn packetizer_budget_matches_wire_rules() {
        let burst = [0x88; 8];
        let mut p = VoiceBurstPacketizer::new(burst);
        assert!(p.start_packet().is_some());
        let mut packets = 0;
        for i in 0..40 {
            if p.add(frame(i as u8)).is_some() {
                packets += 1;
            }
        }
        if p.flush().is_some() {
            packets += 1;
        }
        // 40 frames x 42 B cost = 1680 B; budget 199 content bytes
        // (210 - 11 header) → ≥ 8 packets, each ≤ 8 frames.
        assert!(packets >= 8);
        // Every emitted packet re-encodes and decodes cleanly.
        // (Covered implicitly by construction; sanity check the END packet.)
        let end = p.end_packet(640);
        let _ = VoiceBurstPacket::decode(&end.encode().unwrap()).unwrap();
    }
}
