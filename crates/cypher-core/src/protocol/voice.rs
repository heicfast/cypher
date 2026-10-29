//! Push-to-talk voice bursts (public `voiceFrame` 0x29 / private Noise
//! payload 0x08).
//!
//! Payload: `[8 B burstID][u16 BE seq][u8 flags][payload]`.
//! * `0x01` START — payload = codec u8 (only 0x01 = AAC-LC 16 kHz mono)
//! * `0x02` END — payload = `u16 BE totalDataPackets ‖ u32 BE durationMs`
//! * `0x04` CANCELED — empty payload
//! * `0x00` data — payload = repeated `[u16 BE len][AAC frame]`, 1–8 frames

use crate::consts;
use crate::util::be;

pub const FLAG_START: u8 = 0x01;
pub const FLAG_END: u8 = 0x02;
pub const FLAG_CANCELED: u8 = 0x04;
pub const FLAG_DATA: u8 = 0x00;
pub const CODEC_AAC_LC: u8 = 0x01;
pub const MAX_FRAMES_PER_PACKET: usize = 8;
pub const MAX_BURST_CONTENT_BYTES: usize = consts::PTT_MAX_BURST_CONTENT_BYTES;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct VoiceBurstPacket {
    pub burst_id: [u8; 8],
    pub seq: u16,
    pub flags: u8,
    pub payload: Vec<u8>,
}

impl VoiceBurstPacket {
    pub fn start(burst_id: [u8; 8], codec: u8) -> Self {
        VoiceBurstPacket {
            burst_id,
            seq: 0,
            flags: FLAG_START,
            payload: vec![codec],
        }
    }

    pub fn data(burst_id: [u8; 8], seq: u16, frames: &[Vec<u8>]) -> Option<Self> {
        if frames.is_empty() || frames.len() > MAX_FRAMES_PER_PACKET || frames.iter().any(|f| f.is_empty()) {
            return None;
        }
        let mut payload = Vec::new();
        for frame in frames {
            be::put_u16(&mut payload, frame.len() as u16);
            payload.extend_from_slice(frame);
        }
        Some(VoiceBurstPacket {
            burst_id,
            seq,
            flags: FLAG_DATA,
            payload,
        })
    }

    pub fn end(burst_id: [u8; 8], total_data_packets: u16, duration_ms: u32) -> Self {
        let mut payload = Vec::with_capacity(6);
        be::put_u16(&mut payload, total_data_packets);
        be::put_u32(&mut payload, duration_ms);
        VoiceBurstPacket {
            burst_id,
            seq: 0,
            flags: FLAG_END,
            payload,
        }
    }

    pub fn canceled(burst_id: [u8; 8]) -> Self {
        VoiceBurstPacket {
            burst_id,
            seq: 0,
            flags: FLAG_CANCELED,
            payload: Vec::new(),
        }
    }

    pub fn encode(&self) -> Option<Vec<u8>> {
        if self.payload.len() > MAX_BURST_CONTENT_BYTES {
            return None;
        }
        let mut out = Vec::with_capacity(8 + 2 + 1 + self.payload.len());
        out.extend_from_slice(&self.burst_id);
        be::put_u16(&mut out, self.seq);
        out.push(self.flags);
        out.extend_from_slice(&self.payload);
        Some(out)
    }

    pub fn decode(data: &[u8]) -> Option<Self> {
        if data.len() < 11 {
            return None;
        }
        let burst_id = data[..8].try_into().ok()?;
        let seq = u16::from_be_bytes(data[8..10].try_into().ok()?);
        let flags = data[10];
        let payload = data[11..].to_vec();
        if payload.len() > MAX_BURST_CONTENT_BYTES {
            return None;
        }
        Some(VoiceBurstPacket {
            burst_id,
            seq,
            flags,
            payload,
        })
    }

    /// Decode data-packet frames (only meaningful when `flags == FLAG_DATA`).
    pub fn decode_frames(&self) -> Option<Vec<Vec<u8>>> {
        if self.flags != FLAG_DATA {
            return None;
        }
        let mut frames = Vec::new();
        let mut off = 0usize;
        while off < self.payload.len() {
            if self.payload.len() - off < 2 {
                return None;
            }
            let len = u16::from_be_bytes(self.payload[off..off + 2].try_into().ok()?) as usize;
            off += 2;
            if len == 0 || self.payload.len() - off < len {
                return None;
            }
            frames.push(self.payload[off..off + len].to_vec());
            off += len;
        }
        if frames.is_empty() || frames.len() > MAX_FRAMES_PER_PACKET {
            return None;
        }
        Some(frames)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn start_end_cancel_round_trip() {
        let id = [7u8; 8];
        let start = VoiceBurstPacket::start(id, CODEC_AAC_LC);
        let encoded = start.encode().unwrap();
        assert_eq!(encoded.len(), 12);
        assert_eq!(VoiceBurstPacket::decode(&encoded).unwrap(), start);

        let end = VoiceBurstPacket::end(id, 42, 5300);
        let encoded = end.encode().unwrap();
        assert_eq!(VoiceBurstPacket::decode(&encoded).unwrap(), end);

        let canceled = VoiceBurstPacket::canceled(id);
        let encoded = canceled.encode().unwrap();
        assert_eq!(encoded.len(), 11);
        assert_eq!(VoiceBurstPacket::decode(&encoded).unwrap(), canceled);
    }

    #[test]
    fn data_frames_round_trip() {
        let id = [9u8; 8];
        let frames: Vec<Vec<u8>> = (1..=3).map(|i| vec![i; 30 * i as usize]).collect();
        let packet = VoiceBurstPacket::data(id, 1, &frames).unwrap();
        let encoded = packet.encode().unwrap();
        let decoded = VoiceBurstPacket::decode(&encoded).unwrap();
        assert_eq!(decoded.decode_frames().unwrap(), frames);
    }

    #[test]
    fn frame_limits_enforced() {
        let id = [1u8; 8];
        let empty: Vec<Vec<u8>> = vec![];
        assert!(VoiceBurstPacket::data(id, 1, &empty).is_none());
        let too_many: Vec<Vec<u8>> = (0..9).map(|i| vec![i; 10]).collect();
        assert!(VoiceBurstPacket::data(id, 1, &too_many).is_none());
        let with_empty: Vec<Vec<u8>> = vec![vec![], vec![1]];
        assert!(VoiceBurstPacket::data(id, 1, &with_empty).is_none());
        // Truncated frame list rejected on decode.
        let mut bad = VoiceBurstPacket::data(id, 1, &vec![vec![1, 2, 3]]).unwrap();
        bad.payload.truncate(bad.payload.len() - 1);
        assert!(bad.decode_frames().is_none());
    }

    #[test]
    fn content_budget_enforced() {
        let id = [2u8; 8];
        let mut pkt = VoiceBurstPacket::data(id, 1, &vec![vec![0xAA; 100]]).unwrap();
        pkt.payload = vec![0xBB; MAX_BURST_CONTENT_BYTES + 1];
        assert!(pkt.encode().is_none());
    }
}
