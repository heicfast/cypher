//! `PeerCapabilities` — the u64 feature bitfield advertised in announces.
//!
//! Little-endian **minimal** encoding on the wire (trailing zero bytes
//! dropped, at least one byte); unknown bits preserved on decode.

#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub struct PeerCapabilities(pub u64);

impl PeerCapabilities {
    pub const PREKEYS: u64 = 1 << 0;
    pub const WIFI_BULK: u64 = 1 << 1;
    pub const GATEWAY: u64 = 1 << 2;
    pub const GROUPS: u64 = 1 << 3;
    pub const BOARD: u64 = 1 << 4;
    pub const VOUCH: u64 = 1 << 5;
    pub const MESH_DIAGNOSTICS: u64 = 1 << 6;
    pub const BRIDGE: u64 = 1 << 7;
    pub const PRIVATE_MEDIA: u64 = 1 << 8;
    pub const PRIVATE_MEDIA_RECEIPTS: u64 = 1 << 9;
    /// Burned: never advertised (historic `nonDestructiveNoiseReplacement`).
    pub const RESERVED_BIT_10: u64 = 1 << 10;

    pub fn contains(self, bit: u64) -> bool {
        self.0 & bit != 0
    }

    pub fn insert(&mut self, bit: u64) {
        self.0 |= bit;
    }

    pub fn remove(&mut self, bit: u64) {
        self.0 &= !bit;
    }

    /// The set Cypher advertises (mesh feature parity with bitchat iOS:
    /// vouch, prekeys, groups, privateMedia, privateMediaReceipts).
    pub fn local_supported() -> Self {
        let mut caps = PeerCapabilities::default();
        caps.insert(Self::VOUCH);
        caps.insert(Self::PREKEYS);
        caps.insert(Self::GROUPS);
        caps.insert(Self::PRIVATE_MEDIA);
        caps.insert(Self::PRIVATE_MEDIA_RECEIPTS);
        caps
    }

    /// Minimal little-endian encoding: 1–8 bytes, trailing zeros trimmed.
    pub fn encode(self) -> Vec<u8> {
        let mut bytes = self.0.to_le_bytes().to_vec();
        while bytes.len() > 1 && *bytes.last().unwrap() == 0 {
            bytes.pop();
        }
        bytes
    }

    /// Decode: keeps the low 64 bits of longer values, ignores trailing
    /// garbage; returns `None` for empty input.
    pub fn decode(bytes: &[u8]) -> Option<Self> {
        if bytes.is_empty() || bytes.len() > 8 {
            return None;
        }
        let mut raw = [0u8; 8];
        let n = bytes.len().min(8);
        raw[..n].copy_from_slice(&bytes[..n]);
        Some(PeerCapabilities(u64::from_le_bytes(raw)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn minimal_encoding() {
        assert_eq!(PeerCapabilities(0).encode(), vec![0x00]);
        assert_eq!(PeerCapabilities(1).encode(), vec![0x01]);
        assert_eq!(PeerCapabilities(0x0100).encode(), vec![0x00, 0x01]);
        assert_eq!(PeerCapabilities(0xFF).encode(), vec![0xFF]);
    }

    #[test]
    fn decode_preserves_unknown_bits() {
        // Future bits [0x80, 0x01] → rawValue 0x0180 preserved.
        let caps = PeerCapabilities::decode(&[0x80, 0x01]).unwrap();
        assert_eq!(caps.0, 0x0180);
        assert!(caps.contains(0x0080));
        assert!(caps.contains(0x0100));
        assert!(!caps.contains(PeerCapabilities::PREKEYS));
    }

    #[test]
    fn round_trip() {
        let caps = PeerCapabilities::local_supported();
        let decoded = PeerCapabilities::decode(&caps.encode()).unwrap();
        assert_eq!(caps, decoded);
        assert!(decoded.contains(PeerCapabilities::VOUCH));
        assert!(decoded.contains(PeerCapabilities::PRIVATE_MEDIA_RECEIPTS));
        assert!(!decoded.contains(PeerCapabilities::RESERVED_BIT_10));
    }

    #[test]
    fn rejects_empty_and_long() {
        assert!(PeerCapabilities::decode(&[]).is_none());
        assert!(PeerCapabilities::decode(&[0u8; 9]).is_none());
    }
}
