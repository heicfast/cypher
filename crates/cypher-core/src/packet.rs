//! `BitchatPacket` and the `BinaryProtocol` wire codec — byte-exact port of
//! bitchat's frame format.
//!
//! ```text
//! v1 header (14 B): version(1) type(1) ttl(1) timestamp_ms_u64_BE(8) flags(1) payloadLen_u16_BE(2)
//! v2 header (16 B): same but payloadLen_u32_BE(4)
//! body: senderID(8) [recipientID(8) if flag 0x01] [routeCount(1)+hops×8 if v2 & flag 0x08]
//!       [originalSize u16/u32 BE if flag 0x04] payload [signature(64) if flag 0x02]
//! ```
//!
//! Signatures (Ed25519) cover a re-encoding of the packet with `ttl = 0`,
//! `isRSR = false`, and the signature omitted, PKCS#7-padded — **not** the raw
//! wire bytes (see `docs/courier-test-vectors.json` in bitchat).
//!
//! ## Originator byte preservation
//!
//! DEFLATE output is not canonical across encoders (Apple's raw deflate, Java's
//! `Deflater`, flate2), so a decoded packet keeps the exact payload bytes it
//! arrived with ([`WirePayload`]). Re-encoding — including the signature
//! preimage and relay TTL decrements — reuses those bytes so signatures stay
//! valid across clients and hops. Locally-authored packets compress once at
//! encode time.

use crate::compression;
use crate::padding;
use crate::protocol::limits;
use crate::types::flags;

pub const V1_HEADER_SIZE: usize = 14;
pub const V2_HEADER_SIZE: usize = 16;
pub const SENDER_ID_SIZE: usize = 8;
pub const RECIPIENT_ID_SIZE: usize = 8;
pub const SIGNATURE_SIZE: usize = 64;

/// The payload bytes exactly as carried on the wire, kept so re-encodes are
/// byte-identical (signature preimage stability across clients and relays).
#[derive(Clone, Debug)]
pub struct WirePayload {
    /// Payload region bytes as carried (compressed form when `compressed`).
    pub bytes: Vec<u8>,
    /// Whether these bytes are DEFLATE-compressed on the wire.
    pub compressed: bool,
    /// The logical (decompressed) payload these bytes decode to.
    pub for_payload: Vec<u8>,
}

/// The core packet structure for all Cypher/bitchat mesh protocol messages.
#[derive(Clone, Debug)]
pub struct BitchatPacket {
    pub version: u8,
    pub packet_type: u8,
    pub sender_id: [u8; 8],
    pub recipient_id: Option<[u8; 8]>,
    /// Milliseconds since the Unix epoch (big-endian on the wire).
    pub timestamp: u64,
    /// Logical payload (decompressed when the wire form is compressed).
    pub payload: Vec<u8>,
    pub signature: Option<[u8; 64]>,
    pub ttl: u8,
    /// Source route: intermediate hops only (v2 frames).
    pub route: Option<Vec<[u8; 8]>>,
    /// Request-sync-response marker (excluded from signatures).
    pub is_rsr: bool,
    /// Originator wire bytes (set by [`BinaryProtocol::decode`]).
    pub wire_payload: Option<WirePayload>,
}

impl BitchatPacket {
    /// Build a fresh (uncompressed, unsigned) packet.
    pub fn new(
        packet_type: u8,
        sender_id: [u8; 8],
        recipient_id: Option<[u8; 8]>,
        timestamp: u64,
        payload: Vec<u8>,
        ttl: u8,
    ) -> Self {
        BitchatPacket {
            version: 1,
            packet_type,
            sender_id,
            recipient_id,
            timestamp,
            payload,
            signature: None,
            ttl,
            route: None,
            is_rsr: false,
            wire_payload: None,
        }
    }

    /// Wire encoding (optionally PKCS#7-padded toward the 256/512/1024/2048
    /// buckets — bitchat pads only Noise frames on the wire).
    pub fn to_binary_data(&self, pad_frame: bool) -> Option<Vec<u8>> {
        BinaryProtocol::encode(self, pad_frame)
    }

    /// The signature preimage: re-encoded with `ttl = 0`, `isRSR = false`,
    /// signature omitted, **always padded**. Signatures never cover TTL (it
    /// mutates per hop) or the RSR flag.
    pub fn to_binary_data_for_signing(&self) -> Option<Vec<u8>> {
        let unsigned = BitchatPacket {
            ttl: 0,
            signature: None,
            is_rsr: false,
            ..self.clone()
        };
        BinaryProtocol::encode(&unsigned, true)
    }

    /// Decode a wire frame (tries raw, then after padding removal).
    pub fn from_binary(data: &[u8]) -> Option<Self> {
        BinaryProtocol::decode(data)
    }

    /// The message identity key used for dedup on receive:
    /// `"<senderHex>-<timestamp>-<type>-<sha256(payload)[0..4] hex>"`.
    pub fn message_identity(&self) -> String {
        let digest = crate::util::sha256(&self.payload);
        format!(
            "{}-{}-{}-{}",
            crate::util::hex_encode(&self.sender_id),
            self.timestamp,
            self.packet_type,
            crate::util::hex_encode(&digest[..4])
        )
    }
}

/// Binary wire codec. All multi-byte values big-endian.
pub struct BinaryProtocol;

impl BinaryProtocol {
    pub fn header_size(version: u8) -> Option<usize> {
        match version {
            1 => Some(V1_HEADER_SIZE),
            2 => Some(V2_HEADER_SIZE),
            _ => None,
        }
    }

    fn length_field_size(version: u8) -> usize {
        if version == 2 {
            4
        } else {
            2
        }
    }

    /// Encode a packet. Returns `None` on impossible frames (bad version,
    /// v1 payload region overflow, oversized v1 compressed original size).
    pub fn encode(packet: &BitchatPacket, pad_frame: bool) -> Option<Vec<u8>> {
        let version = packet.version;
        if version != 1 && version != 2 {
            return None;
        }

        // ---- payload region: reuse originator bytes or compress fresh ----
        let mut payload: Vec<u8> = packet.payload.clone();
        let mut is_compressed = false;
        let mut original_payload_size: Option<usize> = None;

        if let Some(wire) = &packet.wire_payload {
            if wire.for_payload == packet.payload {
                if wire.compressed {
                    payload = wire.bytes.clone();
                    original_payload_size = Some(packet.payload.len());
                    is_compressed = true;
                }
                // Uncompressed on the wire: keep it that way.
            } else if compression::should_compress(&packet.payload) {
                if let Some(compressed) = compression::compress(&packet.payload) {
                    original_payload_size = Some(packet.payload.len());
                    payload = compressed;
                    is_compressed = true;
                }
            }
        } else if compression::should_compress(&packet.payload) {
            if let Some(compressed) = compression::compress(&packet.payload) {
                original_payload_size = Some(packet.payload.len());
                payload = compressed;
                is_compressed = true;
            }
        }

        let length_field_bytes = Self::length_field_size(version);

        // ---- route: v2 only, sanitized to 8-byte hops ----
        let original_route: &[[u8; 8]] = if version >= 2 {
            packet.route.as_deref().unwrap_or(&[])
        } else {
            &[]
        };
        let sanitized_route: Vec<[u8; 8]> = original_route
            .iter()
            .map(|hop| crate::peer_id::sanitize_wire_id(hop))
            .collect();
        if sanitized_route.len() > 255 {
            return None;
        }
        let has_route = !sanitized_route.is_empty();
        let route_length = if has_route {
            1 + sanitized_route.len() * SENDER_ID_SIZE
        } else {
            0
        };

        let original_size_field_bytes = if is_compressed { length_field_bytes } else { 0 };
        // Header payloadLength covers payload + compression preamble, NOT route.
        let payload_data_size = payload.len() + original_size_field_bytes;

        if version == 1 && payload_data_size > u16::MAX as usize {
            return None;
        }
        if version == 2 && payload_data_size > u32::MAX as usize {
            return None;
        }
        if version == 1 && original_payload_size.unwrap_or(0) > u16::MAX as usize {
            return None;
        }

        let header_size = Self::header_size(version)?;
        let estimated_signature = if packet.signature.is_some() {
            SIGNATURE_SIZE
        } else {
            0
        };
        let mut data = Vec::with_capacity(
            header_size
                + SENDER_ID_SIZE
                + if packet.recipient_id.is_some() {
                    RECIPIENT_ID_SIZE
                } else {
                    0
                }
                + route_length
                + payload_data_size
                + estimated_signature
                + 255,
        );

        data.push(version);
        data.push(packet.packet_type);
        data.push(packet.ttl);
        data.extend_from_slice(&packet.timestamp.to_be_bytes());

        let mut f: u8 = 0;
        if packet.recipient_id.is_some() {
            f |= flags::HAS_RECIPIENT;
        }
        if packet.signature.is_some() {
            f |= flags::HAS_SIGNATURE;
        }
        if is_compressed {
            f |= flags::IS_COMPRESSED;
        }
        if has_route && version >= 2 {
            f |= flags::HAS_ROUTE;
        }
        if packet.is_rsr {
            f |= flags::IS_RSR;
        }
        data.push(f);

        if version == 2 {
            data.extend_from_slice(&(payload_data_size as u32).to_be_bytes());
        } else {
            data.extend_from_slice(&(payload_data_size as u16).to_be_bytes());
        }

        data.extend_from_slice(&packet.sender_id[..SENDER_ID_SIZE]);
        if let Some(recipient) = packet.recipient_id {
            data.extend_from_slice(&recipient[..RECIPIENT_ID_SIZE]);
        }

        if has_route {
            data.push(sanitized_route.len() as u8);
            for hop in &sanitized_route {
                data.extend_from_slice(hop);
            }
        }

        if is_compressed {
            if let Some(original) = original_payload_size {
                if version == 2 {
                    data.extend_from_slice(&(original as u32).to_be_bytes());
                } else {
                    data.extend_from_slice(&(original as u16).to_be_bytes());
                }
            }
        }
        data.extend_from_slice(&payload);

        if let Some(signature) = packet.signature {
            let mut sig = [0u8; SIGNATURE_SIZE];
            let n = signature.len().min(SIGNATURE_SIZE);
            sig[..n].copy_from_slice(&signature[..n]);
            data.extend_from_slice(&sig);
        }

        if pad_frame {
            return Some(padding::pad(&data));
        }
        Some(data)
    }

    /// Decode a frame; tolerant of PKCS#7 padding and trailing bytes.
    pub fn decode(data: &[u8]) -> Option<BitchatPacket> {
        if let Some(pkt) = Self::decode_core(data) {
            return Some(pkt);
        }
        let unpadded = padding::unpad(data);
        if unpadded == data {
            return None;
        }
        Self::decode_core(&unpadded)
    }

    fn decode_core(raw: &[u8]) -> Option<BitchatPacket> {
        if raw.len() < V1_HEADER_SIZE + SENDER_ID_SIZE {
            return None;
        }
        let version = raw[0];
        if version != 1 && version != 2 {
            return None;
        }
        let header_size = Self::header_size(version)?;
        if raw.len() < header_size + SENDER_ID_SIZE {
            return None;
        }
        let length_field_bytes = Self::length_field_size(version);

        let packet_type = raw[1];
        let ttl = raw[2];
        let timestamp = u64::from_be_bytes(raw[3..11].try_into().ok()?);
        let f = raw[11];
        let has_recipient = f & flags::HAS_RECIPIENT != 0;
        let has_signature = f & flags::HAS_SIGNATURE != 0;
        let is_compressed = f & flags::IS_COMPRESSED != 0;
        // HAS_ROUTE is only meaningful for v2+; ignored on v1.
        let has_route = version >= 2 && f & flags::HAS_ROUTE != 0;
        let is_rsr = f & flags::IS_RSR != 0;

        let payload_length = if version == 2 {
            u32::from_be_bytes(raw[12..16].try_into().ok()?) as usize
        } else {
            u16::from_be_bytes(raw[12..14].try_into().ok()?) as usize
        };
        if payload_length > crate::consts::FILE_MAX_FRAMED_BYTES {
            return None;
        }

        let mut off = header_size;
        let mut take = |n: usize| -> Option<&[u8]> {
            let slice = raw.get(off..off.checked_add(n)?)?;
            off += n;
            Some(slice)
        };

        let sender_id: [u8; 8] = take(SENDER_ID_SIZE)?.try_into().ok()?;
        let recipient_id: Option<[u8; 8]> = if has_recipient {
            Some(take(RECIPIENT_ID_SIZE)?.try_into().ok()?)
        } else {
            None
        };

        let route: Option<Vec<[u8; 8]>> = if has_route {
            let count = take(1)?[0] as usize;
            if count > 0 {
                let mut hops = Vec::with_capacity(count);
                for _ in 0..count {
                    hops.push(take(SENDER_ID_SIZE)?.try_into().ok()?);
                }
                Some(hops)
            } else {
                Some(Vec::new())
            }
        } else {
            None
        };

        let max_payload = limits::max_payload_bytes(packet_type);
        let (payload, wire_payload): (Vec<u8>, WirePayload) = if is_compressed {
            if payload_length < length_field_bytes {
                return None;
            }
            let original_size = if version == 2 {
                u32::from_be_bytes(take(4)?.try_into().ok()?) as usize
            } else {
                u16::from_be_bytes(take(2)?.try_into().ok()?) as usize
            };
            if original_size > max_payload {
                return None;
            }
            let compressed_size = payload_length - length_field_bytes;
            if compressed_size == 0 {
                return None;
            }
            let compressed = take(compressed_size)?.to_vec();
            if original_size > compressed_size.saturating_mul(limits::MAX_DEFLATE_RATIO) {
                return None;
            }
            let decompressed = compression::decompress(&compressed, original_size, max_payload)?;
            let wire = WirePayload {
                bytes: compressed,
                compressed: true,
                for_payload: decompressed.clone(),
            };
            (decompressed, wire)
        } else {
            if payload_length > max_payload {
                return None;
            }
            let raw_payload = take(payload_length)?.to_vec();
            let wire = WirePayload {
                bytes: raw_payload.clone(),
                compressed: false,
                for_payload: raw_payload.clone(),
            };
            (raw_payload, wire)
        };

        let signature: Option<[u8; 64]> = if has_signature {
            Some(take(SIGNATURE_SIZE)?.try_into().ok()?)
        } else {
            None
        };

        if off > raw.len() {
            return None;
        }

        Some(BitchatPacket {
            version,
            packet_type,
            sender_id,
            recipient_id,
            timestamp,
            payload,
            signature,
            ttl,
            route,
            is_rsr,
            wire_payload: Some(wire_payload),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn courier_vector_frame() -> Vec<u8> {
        // The unsigned, unpadded frame from bitchat's courier-test-vectors.json
        // (packet type 0x04, ttl 7, ts 1750000000000, flags 0x01, payload 74 B).
        crate::testutil::unhex(
            "010407000001977420dc0001004a112233445566778899aabbccddeeff0001001000010203\
             0405060708090a0b0c0d0e0f020008000001a3185c500003001e636f75726965722d7665\
             63746f722d636970686572746578742d303030310400010405000411223344",
        )
    }

    #[test]
    fn golden_vector_decode() {
        let frame = courier_vector_frame();
        assert_eq!(frame.len(), 104);
        let pkt = BitchatPacket::from_binary(&frame).expect("decodes golden vector");
        assert_eq!(pkt.version, 1);
        assert_eq!(pkt.packet_type, 0x04);
        assert_eq!(pkt.ttl, 7);
        assert_eq!(pkt.timestamp, 1_750_000_000_000);
        assert_eq!(
            pkt.sender_id,
            [0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88]
        );
        assert_eq!(
            pkt.recipient_id,
            Some([0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff, 0x00])
        );
        assert_eq!(pkt.payload.len(), 74);
        assert!(pkt.signature.is_none());
    }

    #[test]
    fn golden_vector_reencode_identical() {
        let frame = courier_vector_frame();
        let pkt = BitchatPacket::from_binary(&frame).unwrap();
        let re = pkt.to_binary_data(false).expect("re-encodes");
        assert_eq!(re, frame, "decoded packet must re-encode byte-identically");
    }

    #[test]
    fn golden_vector_signing_preimage() {
        // toBinaryDataForSigning: ttl=0, no signature, PKCS#7-padded to 256
        // with 152 bytes of 0x98 (bitchat courier-test-vectors.json).
        let frame = courier_vector_frame();
        let pkt = BitchatPacket::from_binary(&frame).unwrap();
        let preimage = pkt.to_binary_data_for_signing().unwrap();
        assert_eq!(preimage.len(), 256);
        assert_eq!(preimage[2], 0x00, "ttl zeroed");
        assert_eq!(preimage[11], 0x01, "no-signature flag byte");
        for &b in &preimage[104..] {
            assert_eq!(b, 0x98);
        }
        // And the full preimage matches the vector byte-for-byte.
        let expected = crate::testutil::unhex(
            "010400000001977420dc0001004a112233445566778899aabbccddeeff00010010000102030405\
             060708090a0b0c0d0e0f020008000001a3185c500003001e636f75726965722d766563746f72\
             2d636970686572746578742d303030310400010405000411223344",
        );
        let pad = vec![0x98u8; 152];
        let mut full = expected;
        full.extend_from_slice(&pad);
        assert_eq!(preimage, full);
    }

    #[test]
    fn round_trip_v1_basic() {
        let mut pkt = BitchatPacket::new(
            0x02,
            [1, 2, 3, 4, 5, 6, 7, 8],
            None,
            1_750_000_000_123,
            b"hello mesh".to_vec(),
            7,
        );
        pkt.signature = Some([0xAB; 64]);
        let wire = pkt.to_binary_data(false).unwrap();
        let back = BitchatPacket::from_binary(&wire).unwrap();
        assert_eq!(back.packet_type, pkt.packet_type);
        assert_eq!(back.sender_id, pkt.sender_id);
        assert_eq!(back.recipient_id, None);
        assert_eq!(back.timestamp, pkt.timestamp);
        assert_eq!(back.payload, pkt.payload);
        assert_eq!(back.signature, Some([0xAB; 64]));
        assert_eq!(back.ttl, 7);
        assert!(!back.wire_payload.as_ref().unwrap().compressed);
        // Re-encode is stable.
        assert_eq!(back.to_binary_data(false).unwrap(), wire);
    }

    #[test]
    fn round_trip_v2_route() {
        let mut pkt = BitchatPacket::new(
            0x11,
            [1, 1, 1, 1, 1, 1, 1, 1],
            Some([9, 9, 9, 9, 9, 9, 9, 9]),
            42,
            vec![7u8; 64],
            7,
        );
        pkt.version = 2;
        pkt.route = Some(vec![[2; 8], [3; 8]]);
        let wire = pkt.to_binary_data(false).unwrap();
        assert_eq!(wire[0], 2);
        let back = BitchatPacket::from_binary(&wire).unwrap();
        assert_eq!(back.version, 2);
        assert_eq!(back.route, Some(vec![[2; 8], [3; 8]]));
        assert_eq!(back.to_binary_data(false).unwrap(), wire);
    }

    #[test]
    fn compressed_round_trip_and_preimage_stability() {
        // A long repetitive payload compresses; the decoded packet must keep
        // the originator's compressed bytes for byte-identical re-encode.
        let pkt = BitchatPacket::new(
            0x02,
            [1, 2, 3, 4, 5, 6, 7, 8],
            None,
            1_750_000_000_456,
            vec![b'z'; 400],
            7,
        );
        let wire = pkt.to_binary_data(false).unwrap();
        assert!(wire.len() < 400 + 14 + 8, "must have compressed");
        let back = BitchatPacket::from_binary(&wire).unwrap();
        assert_eq!(back.payload, vec![b'z'; 400]);
        assert!(back.wire_payload.as_ref().unwrap().compressed);
        // Byte-identical re-encode even though a fresh compress would also work.
        assert_eq!(back.to_binary_data(false).unwrap(), wire);
        // And if the relay changes only TTL, re-encode differs only in TTL byte.
        let mut relayed = back.clone();
        relayed.ttl -= 1;
        let relay_wire = relayed.to_binary_data(false).unwrap();
        let mut expected = wire.clone();
        expected[2] = 6;
        assert_eq!(relay_wire, expected);
    }

    #[test]
    fn v1_oversize_rejected() {
        let pkt = BitchatPacket::new(0x02, [0; 8], None, 1, vec![0u8; 70_000], 7);
        assert!(pkt.to_binary_data(false).is_none());
        // v2 handles it.
        let mut pkt2 = pkt;
        pkt2.version = 2;
        assert!(pkt2.to_binary_data(false).is_some());
    }

    #[test]
    fn decode_garbage_rejected() {
        assert!(BitchatPacket::from_binary(&[]).is_none());
        assert!(BitchatPacket::from_binary(&[0u8; 20]).is_none());
        let mut bad = courier_vector_frame();
        bad[0] = 3; // bad version
        assert!(BitchatPacket::from_binary(&bad).is_none());
    }

    #[test]
    fn payload_ceiling_enforced() {
        // announce payload cap is 4 KiB (uncompressed)
        let pkt = BitchatPacket::new(0x01, [0; 8], None, 1, vec![0u8; 5_000], 7);
        let wire = pkt.to_binary_data(false).unwrap();
        assert!(BitchatPacket::from_binary(&wire).is_none());
    }

    #[test]
    fn message_identity_format() {
        let pkt = BitchatPacket::new(0x02, [0x11; 8], None, 1000, b"abc".to_vec(), 7);
        let id = pkt.message_identity();
        let digest = crate::util::sha256(b"abc");
        assert_eq!(
            id,
            format!(
                "1111111111111111-1000-2-{}",
                crate::util::hex_encode(&digest[..4])
            )
        );
    }
}
