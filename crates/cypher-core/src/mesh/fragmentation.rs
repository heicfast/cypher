//! Fragmentation: outbound planning and inbound reassembly.
//!
//! Fragment payload: `[8 B fragmentID][u16 BE index][u16 BE total][u8
//! originalType][chunk]`. Chunk size defaults to 469; routed v2 packets use
//! `max(64, 512 − overhead)`. Reassembly holds ≤128 concurrent assemblies,
//! each bounded by the per-type frame cap, with a 30 s lifetime and 5 s
//! stall detection triggering targeted REQUEST_SYNC.

use std::collections::HashMap;

use crate::consts;
use crate::packet::{BitchatPacket, WirePayload};
use crate::protocol::limits;
use crate::types::MessageType;
use crate::util::be;

/// Parsed fragment header.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FragmentHeader {
    pub fragment_id: [u8; 8],
    pub index: u16,
    pub total: u16,
    pub original_type: u8,
}

impl FragmentHeader {
    pub const MIN_BYTES: usize = 13;

    pub fn decode(payload: &[u8]) -> Option<Self> {
        if payload.len() < Self::MIN_BYTES {
            return None;
        }
        Some(FragmentHeader {
            fragment_id: payload[..8].try_into().ok()?,
            index: u16::from_be_bytes(payload[8..10].try_into().ok()?),
            total: u16::from_be_bytes(payload[10..12].try_into().ok()?),
            original_type: payload[12],
        })
    }

    /// Validation: `total > 0`, `total ≤ 10_000`, `index < total`.
    pub fn is_valid(&self) -> bool {
        self.total > 0 && self.total <= consts::FRAGMENT_MAX_TOTAL && self.index < self.total
    }
}

/// Compute the chunk size for an outbound packet (route-aware).
pub fn chunk_size(route_hops: usize, requested_max: Option<usize>) -> usize {
    let calculated = if route_hops > 0 {
        // v2 routed fragments: 512 − (16 header + 8 sender + 8 recipient +
        // 1 + 8·hops route + 13 fragment header + 16 length slack)
        consts::BLE_MAX_MTU
            .saturating_sub(16 + 8 + 8 + (1 + 8 * route_hops) + 13 + 16)
            .max(consts::FRAGMENT_MIN_CHUNK)
    } else {
        consts::BLE_DEFAULT_FRAGMENT_SIZE
    };
    match requested_max {
        Some(max) => max
            .max(consts::FRAGMENT_MIN_CHUNK)
            .min(calculated.max(consts::FRAGMENT_MIN_CHUNK)),
        None => calculated.max(consts::FRAGMENT_MIN_CHUNK),
    }
}

/// Plan the fragment train for an outbound packet. Returns the fragment
/// packets (unencoded) in send order.
pub fn plan_fragments(
    packet: &BitchatPacket,
    requested_max_chunk: Option<usize>,
    now_ms: u64,
) -> Option<Vec<BitchatPacket>> {
    // Large payloads cannot ride a v1 frame (u16 payload region); big
    // packets ship as v2 exactly like bitchat's fileTransfer / privateFile.
    let mut packet = packet.clone();
    if packet.version == 1 && packet.payload.len() + 2 > u16::MAX as usize {
        packet.version = 2;
    }
    let packet = &packet;
    let encoded = packet.to_binary_data(false)?;
    let route_hops = packet.route.as_ref().map_or(0, |r| r.len());
    let chunk = chunk_size(route_hops, requested_max_chunk);
    if chunk == 0 {
        return None;
    }
    let total = encoded.len().div_ceil(chunk);
    if total > consts::FRAGMENT_MAX_TOTAL as usize {
        return None;
    }
    // Directed raw fileTransfer must fit the Android 256-fragment ceiling.
    if packet.packet_type == MessageType::FileTransfer.as_u8()
        && packet.recipient_id.is_some()
        && total > consts::PRIVATE_MEDIA_V1_MAX_FRAGMENTS as usize
    {
        return None;
    }

    let mut fragment_id = [0u8; 8];
    use rand_core::RngCore;
    rand_core::OsRng.fill_bytes(&mut fragment_id);

    let version = if route_hops > 0 { 2 } else { 1 };
    let mut out = Vec::with_capacity(total);
    for (index, chunk_bytes) in encoded.chunks(chunk).enumerate() {
        let mut payload = Vec::with_capacity(FragmentHeader::MIN_BYTES + chunk_bytes.len());
        payload.extend_from_slice(&fragment_id);
        be::put_u16(&mut payload, index as u16);
        be::put_u16(&mut payload, total as u16);
        payload.push(packet.packet_type);
        payload.extend_from_slice(chunk_bytes);

        let mut fragment = BitchatPacket::new(
            MessageType::Fragment.as_u8(),
            packet.sender_id,
            // Directed: the target peer; broadcast: the FF marker.
            packet
                .recipient_id
                .or(Some(crate::PeerId::broadcast_marker())),
            packet.timestamp,
            payload,
            packet.ttl,
        );
        fragment.version = version;
        fragment.route = packet.route.clone();
        fragment.is_rsr = packet.is_rsr;
        fragment.wire_payload = Some(WirePayload {
            bytes: fragment.payload.clone(),
            compressed: false,
            for_payload: fragment.payload.clone(),
        });
        let _ = now_ms;
        out.push(fragment);
    }
    Some(out)
}

/// Inbound reassembly buffer (engine-owned).
#[derive(Default)]
pub struct FragmentAssemblyBuffer {
    /// (sender wire id, fragment id) → assembly.
    assemblies: HashMap<([u8; 8], [u8; 8]), Assembly>,
    /// Insertion order for oldest-eviction.
    order: Vec<([u8; 8], [u8; 8])>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum AssemblyOutcome {
    /// A fragment was accepted; the stream continues.
    Progress,
    /// The stream completed; payload is the reassembled packet bytes.
    Complete(Vec<u8>),
    /// The claimed type forged (fragment says X, decoded packet is Y).
    ForgedType,
    /// Projected size exceeded the per-type frame cap.
    Oversized,
}

struct Assembly {
    total: u16,
    #[allow(dead_code)]
    original_type: u8,
    started_ms: u64,
    last_fragment_ms: u64,
    held: HashMap<u16, Vec<u8>>,
}

impl FragmentAssemblyBuffer {
    pub fn new() -> Self {
        Self::default()
    }

    /// Feed one fragment. `now_ms` drives expiry.
    pub fn ingest(&mut self, packet: &BitchatPacket, now_ms: u64) -> Option<AssemblyOutcome> {
        let header = FragmentHeader::decode(&packet.payload)?;
        if !header.is_valid() {
            return None;
        }
        let key = (packet.sender_id, header.fragment_id);

        // Capacity: starting a new assembly while at the in-flight limit
        // evicts the oldest first (bitchat evicts before inserting).
        if !self.assemblies.contains_key(&key)
            && self.assemblies.len() >= consts::BLE_MAX_IN_FLIGHT_ASSEMBLIES
        {
            if let Some(oldest) = self
                .assemblies
                .iter()
                .min_by_key(|(_, a)| a.started_ms)
                .map(|(k, _)| *k)
            {
                self.remove(&oldest);
            }
        }

        let max_frame = limits::max_frame_bytes(header.original_type);
        let entry = self.assemblies.entry(key).or_insert_with(|| {
            self.order.push(key);
            Assembly {
                total: header.total,
                original_type: header.original_type,
                started_ms: now_ms,
                last_fragment_ms: now_ms,
                held: HashMap::new(),
            }
        });

        // Projected size bound (current bytes − replaced index + new chunk).
        let projected: usize = entry
            .held
            .values()
            .map(|v| v.len())
            .sum::<usize>()
            .saturating_sub(entry.held.get(&header.index).map_or(0, |v| v.len()))
            + packet.payload.len()
            - FragmentHeader::MIN_BYTES;
        if projected + limits::MAX_FRAME_OVERHEAD_BYTES > max_frame {
            self.remove(&key);
            return Some(AssemblyOutcome::Oversized);
        }

        // A new index resets the stall clock; re-delivery does not.
        let is_new_index = !entry.held.contains_key(&header.index);
        entry.held.insert(
            header.index,
            packet.payload[FragmentHeader::MIN_BYTES..].to_vec(),
        );
        if is_new_index {
            entry.last_fragment_ms = now_ms;
        }

        if entry.held.len() == entry.total as usize {
            // Concatenate 0..total.
            let mut assembled = Vec::with_capacity(projected);
            for i in 0..entry.total {
                assembled.extend_from_slice(&entry.held[&i]);
            }
            self.remove(&key);
            // Forged-type check: the decoded packet must carry the claimed type.
            match BitchatPacket::from_binary(&assembled) {
                Some(decoded) if decoded.packet_type == header.original_type => {
                    Some(AssemblyOutcome::Complete(assembled))
                }
                _ => Some(AssemblyOutcome::ForgedType),
            }
        } else {
            Some(AssemblyOutcome::Progress)
        }
    }

    fn remove(&mut self, key: &([u8; 8], [u8; 8])) {
        self.assemblies.remove(key);
        self.order.retain(|k| k != key);
    }

    /// Drop assemblies older than the 30 s lifetime.
    pub fn remove_expired(&mut self, now_ms: u64) {
        let cutoff = now_ms.saturating_sub(consts::BLE_FRAGMENT_LIFETIME_S * 1000);
        let stale: Vec<([u8; 8], [u8; 8])> = self
            .assemblies
            .iter()
            .filter(|(_, a)| a.started_ms < cutoff)
            .map(|(k, _)| *k)
            .collect();
        for key in stale {
            self.remove(&key);
        }
    }

    /// Broadcast streams stalled ≥5 s without a new fragment (per-stream
    /// retry spacing 10 s) — candidates for targeted REQUEST_SYNC.
    pub fn stalled_streams(&self, now_ms: u64, last_retry: &HashMap<[u8; 8], u64>) -> Vec<[u8; 8]> {
        let stall_cutoff = now_ms.saturating_sub(consts::BLE_FRAGMENT_RESYNC_STALL_S * 1000);
        let retry_cutoff = now_ms.saturating_sub(consts::BLE_FRAGMENT_RESYNC_RETRY_S * 1000);
        let mut stalled: Vec<[u8; 8]> = Vec::new();
        for (key, a) in &self.assemblies {
            if a.last_fragment_ms < stall_cutoff {
                let id = key.1;
                let eligible = match last_retry.get(&id) {
                    Some(last) => *last < retry_cutoff,
                    None => true,
                };
                if eligible {
                    stalled.push(id);
                }
            }
        }
        stalled
    }

    pub fn in_flight(&self) -> usize {
        self.assemblies.len()
    }

    pub fn clear(&mut self) {
        self.assemblies.clear();
        self.order.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Pseudo-random (incompressible) payload so the wire frame keeps its
    /// size after the encoder's DEFLATE pass.
    fn entropy(size: usize) -> Vec<u8> {
        let mut x: u64 = 0x9E3779B97F4A7C15;
        (0..size)
            .map(|_| {
                x ^= x << 13;
                x ^= x >> 7;
                x ^= x << 17;
                (x >> 32) as u8
            })
            .collect()
    }

    fn big_packet(size: usize, recipient: Option<[u8; 8]>) -> BitchatPacket {
        let mut p = BitchatPacket::new(
            MessageType::Message.as_u8(),
            [0x22; 8],
            recipient,
            1000,
            entropy(size),
            7,
        );
        p.signature = Some([7; 64]);
        p
    }

    #[test]
    fn plan_and_reassemble_in_order() {
        let packet = big_packet(3000, None);
        let fragments = plan_fragments(&packet, Some(400), 0).unwrap();
        assert!(fragments.len() >= 7);
        let mut buffer = FragmentAssemblyBuffer::new();
        for fragment in &fragments {
            let outcome = buffer.ingest(fragment, 0).unwrap();
            if std::ptr::eq(fragment, fragments.last().unwrap()) {
                match outcome {
                    AssemblyOutcome::Complete(bytes) => {
                        let decoded = BitchatPacket::from_binary(&bytes).unwrap();
                        assert_eq!(decoded.payload, packet.payload);
                        assert_eq!(decoded.packet_type, packet.packet_type);
                    }
                    other => panic!("expected completion, got {other:?}"),
                }
            } else {
                assert_eq!(outcome, AssemblyOutcome::Progress);
            }
        }
    }

    #[test]
    fn reassemble_out_of_order_and_duplicates() {
        let packet = big_packet(2000, None);
        let fragments = plan_fragments(&packet, Some(300), 0).unwrap();
        let mut buffer = FragmentAssemblyBuffer::new();
        // Deliver in reverse order, with a duplicate inserted.
        let mut order: Vec<&BitchatPacket> = fragments.iter().rev().collect();
        order.insert(1, &fragments[1]);
        for (i, fragment) in order.iter().enumerate() {
            let outcome = buffer.ingest(fragment, i as u64);
            if i == order.len() - 1 {
                assert!(matches!(outcome, Some(AssemblyOutcome::Complete(_))));
            } else {
                assert!(matches!(outcome, Some(AssemblyOutcome::Progress)));
            }
        }
    }

    #[test]
    fn forged_type_detected() {
        // Fragment claims type 0x02 but the reassembled packet is 0x01.
        let packet = big_packet(1000, None);
        let mut fragments = plan_fragments(&packet, Some(400), 0).unwrap();
        for f in &mut fragments {
            f.payload[12] = MessageType::Announce.as_u8(); // lie about the type
        }
        let mut buffer = FragmentAssemblyBuffer::new();
        let mut last = None;
        for f in &fragments {
            last = buffer.ingest(f, 0);
        }
        assert_eq!(last, Some(AssemblyOutcome::ForgedType));
    }

    #[test]
    fn chunk_size_rules() {
        assert_eq!(chunk_size(0, None), consts::BLE_DEFAULT_FRAGMENT_SIZE);
        assert_eq!(chunk_size(0, Some(100)), 100);
        assert_eq!(chunk_size(0, Some(10)), consts::FRAGMENT_MIN_CHUNK);
        let routed = chunk_size(2, None);
        assert!(routed >= consts::FRAGMENT_MIN_CHUNK);
        assert!(routed < consts::BLE_DEFAULT_FRAGMENT_SIZE);
    }

    #[test]
    fn directed_file_transfer_256_ceiling() {
        // 600 KiB of incompressible data at chunk 469 → ~1280 fragments,
        // over the directed raw-fileTransfer ceiling.
        let mut p = BitchatPacket::new(
            MessageType::FileTransfer.as_u8(),
            [1; 8],
            Some([2; 8]),
            1,
            entropy(600_000),
            7,
        );
        p.signature = Some([3; 64]);
        assert!(plan_fragments(&p, None, 0).is_none());
        // Broadcast (no recipient) is not bound by the Android ceiling.
        let broadcast = BitchatPacket::new(
            MessageType::FileTransfer.as_u8(),
            [1; 8],
            None,
            1,
            entropy(600_000),
            7,
        );
        assert!(plan_fragments(&broadcast, None, 0).is_some());
    }

    #[test]
    fn expiry_drops_stale_assemblies() {
        let packet = big_packet(1000, None);
        let fragments = plan_fragments(&packet, Some(400), 0).unwrap();
        let mut buffer = FragmentAssemblyBuffer::new();
        buffer.ingest(&fragments[0], 0).unwrap();
        assert_eq!(buffer.in_flight(), 1);
        buffer.remove_expired(consts::BLE_FRAGMENT_LIFETIME_S * 1000 + 1);
        assert_eq!(buffer.in_flight(), 0);
    }
}
