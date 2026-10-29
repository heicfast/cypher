//! Private-message payloads, mesh ping, and stable message identities.

use crate::tlv::{write_short, TlvReader};
use crate::util::{hex_encode, sha256};

/// `privateMessage` (Noise payload 0x01) — TLV with 1-byte lengths:
/// `0x00 messageID (UTF-8 ≤255)`, `0x01 content (UTF-8 ≤255)`.
/// Unknown TLV types reject the decode.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
pub struct PrivateMessagePacket {
    pub message_id: String,
    pub content: String,
}

impl PrivateMessagePacket {
    pub fn encode(&self) -> Option<Vec<u8>> {
        let id = self.message_id.as_bytes();
        let content = self.content.as_bytes();
        if id.len() > 255 || content.len() > 255 {
            return None;
        }
        let mut out = Vec::with_capacity(2 + id.len() + 2 + content.len());
        write_short(&mut out, 0x00, id);
        write_short(&mut out, 0x01, content);
        Some(out)
    }

    pub fn decode(data: &[u8]) -> Option<Self> {
        let mut message_id: Option<String> = None;
        let mut content: Option<String> = None;
        let mut reader = TlvReader::short(data);
        while let Some((tag, value)) = reader.next_record() {
            match tag {
                0x00 => message_id = String::from_utf8(value.to_vec()).ok(),
                0x01 => content = String::from_utf8(value.to_vec()).ok(),
                _ => return None, // unknown TLV types reject the packet
            }
        }
        Some(PrivateMessagePacket {
            message_id: message_id?,
            content: content?,
        })
    }
}

/// Mesh identity for public messages — cross-device-consistent stable ID:
/// `hex(SHA-256(lowercased(senderIDHex) ‖ "|" ‖ String(tsMs) ‖ "|" ‖
/// trimmed(content)))[0..32]`.
pub struct MeshMessageIdentity;

impl MeshMessageIdentity {
    pub fn stable_id(sender_id_hex: &str, timestamp_ms: u64, content: &str) -> String {
        let canonical = format!(
            "{}|{}|{}",
            sender_id_hex.to_lowercase(),
            timestamp_ms,
            content.trim()
        );
        hex_encode(&sha256(canonical.as_bytes()))[..32].to_string()
    }
}

/// `ping`/`pong` (0x26/0x27) payload: `[8-byte random nonce][1-byte
/// originTTL]` (trailing bytes tolerated on decode).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MeshPingPayload {
    pub nonce: [u8; 8],
    pub origin_ttl: u8,
}

impl MeshPingPayload {
    pub const LEN: usize = 9;

    pub fn new(nonce: [u8; 8], origin_ttl: u8) -> Self {
        MeshPingPayload { nonce, origin_ttl }
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(Self::LEN);
        out.extend_from_slice(&self.nonce);
        out.push(self.origin_ttl);
        out
    }

    pub fn decode(data: &[u8]) -> Option<Self> {
        if data.len() < Self::LEN {
            return None;
        }
        Some(MeshPingPayload {
            nonce: data[..8].try_into().ok()?,
            origin_ttl: data[8],
        })
    }

    /// Hop count derived from the origin TTL vs the received TTL.
    pub fn hop_count(origin_ttl: u8, received_ttl: u8) -> Option<u8> {
        if received_ttl > origin_ttl {
            None
        } else {
            Some(origin_ttl - received_ttl + 1)
        }
    }
}

/// `ReadReceipt` binary form (BLE ack path):
/// `[16 B originalMessageID as UUID bytes][16 B receiptID UUID][8 B readerID
/// bytes][8 B timestamp ms BE][u8 len + readerNickname]`. Minimum 49 bytes.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ReadReceipt {
    pub original_message_id: [u8; 16],
    pub receipt_id: [u8; 16],
    pub reader_id: [u8; 8],
    /// Milliseconds since epoch.
    pub timestamp: u64,
    pub reader_nickname: String,
}

impl ReadReceipt {
    pub fn encode(&self) -> Option<Vec<u8>> {
        let nick = self.reader_nickname.as_bytes();
        if nick.len() > 255 {
            return None;
        }
        let mut out = Vec::with_capacity(49 + nick.len());
        out.extend_from_slice(&self.original_message_id);
        out.extend_from_slice(&self.receipt_id);
        out.extend_from_slice(&self.reader_id);
        out.extend_from_slice(&self.timestamp.to_be_bytes());
        out.push(nick.len() as u8);
        out.extend_from_slice(nick);
        Some(out)
    }

    pub fn decode(data: &[u8]) -> Option<Self> {
        if data.len() < 49 {
            return None;
        }
        let original_message_id = data[0..16].try_into().ok()?;
        let receipt_id = data[16..32].try_into().ok()?;
        let reader_id = data[32..40].try_into().ok()?;
        let timestamp = u64::from_be_bytes(data[40..48].try_into().ok()?);
        let nick_len = data[48] as usize;
        if data.len() < 49 + nick_len {
            return None;
        }
        let reader_nickname = String::from_utf8(data[49..49 + nick_len].to_vec()).ok()?;
        Some(ReadReceipt {
            original_message_id,
            receipt_id,
            reader_id,
            timestamp,
            reader_nickname,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn private_message_round_trip() {
        let pm = PrivateMessagePacket {
            message_id: "msg-1".into(),
            content: "hello over noise".into(),
        };
        let encoded = pm.encode().unwrap();
        assert_eq!(PrivateMessagePacket::decode(&encoded).unwrap(), pm);
        // Unknown TLV type rejects.
        let mut bad = encoded.clone();
        bad.extend_from_slice(&[0x02, 0x01, 0x41]);
        assert!(PrivateMessagePacket::decode(&bad).is_none());
    }

    #[test]
    fn stable_id_deterministic_and_case_insensitive() {
        let a = MeshMessageIdentity::stable_id("AABBCCDD", 1000, " hi ");
        let b = MeshMessageIdentity::stable_id("aabbccdd", 1000, "hi");
        assert_eq!(a, b);
        assert_eq!(a.len(), 32);
        let c = MeshMessageIdentity::stable_id("aabbccdd", 1001, "hi");
        assert_ne!(a, c);
    }

    #[test]
    fn ping_round_trip_and_hops() {
        let p = MeshPingPayload::new([9; 8], 7);
        let encoded = p.encode();
        assert_eq!(encoded.len(), 9);
        assert_eq!(MeshPingPayload::decode(&encoded).unwrap(), p);
        // Trailing bytes tolerated.
        let mut padded = encoded.clone();
        padded.extend_from_slice(&[0xAA]);
        assert_eq!(MeshPingPayload::decode(&padded).unwrap(), p);
        assert_eq!(MeshPingPayload::hop_count(7, 7), Some(1));
        assert_eq!(MeshPingPayload::hop_count(7, 5), Some(3));
        assert_eq!(MeshPingPayload::hop_count(7, 8), None);
    }

    #[test]
    fn read_receipt_round_trip() {
        let r = ReadReceipt {
            original_message_id: [1; 16],
            receipt_id: [2; 16],
            reader_id: [3; 8],
            timestamp: 1_750_000_000_000,
            reader_nickname: "bob".into(),
        };
        let encoded = r.encode().unwrap();
        assert_eq!(encoded.len(), 49 + 3);
        assert_eq!(ReadReceipt::decode(&encoded).unwrap(), r);
        assert!(ReadReceipt::decode(&encoded[..48]).is_none());
    }
}
