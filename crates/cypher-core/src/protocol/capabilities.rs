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

/// `AuthenticatedPeerState` (Noise payload 0x21) — versioned TLV, exact port
/// of bitchat's `Packets.swift`:
/// `[version=0x01][0x01][len][capabilities][0x02][32][signingPublicKey]`.
pub fn authenticated_peer_state(
    capabilities: PeerCapabilities,
    signing_public_key: &[u8; 32],
) -> Vec<u8> {
    let caps = capabilities.encode();
    let mut out = Vec::with_capacity(1 + 2 + caps.len() + 2 + 32);
    out.push(0x01); // version
    out.push(0x01); // TLV type: capabilities
    out.push(caps.len() as u8);
    out.extend_from_slice(&caps);
    out.push(0x02); // TLV type: signingPublicKey
    out.push(32);
    out.extend_from_slice(signing_public_key);
    out
}

/// Parse an `AuthenticatedPeerState` payload. Unknown TLVs are skipped for
/// forward compatibility; wrong version / bad lengths / duplicates / missing
/// fields return `None` without touching caller state (parity: Swift decode).
pub fn parse_authenticated_peer_state(data: &[u8]) -> Option<(PeerCapabilities, [u8; 32])> {
    if data.first() != Some(&0x01) {
        return None; // version
    }
    let mut offset = 1usize;
    let mut capabilities: Option<PeerCapabilities> = None;
    let mut signing: Option<[u8; 32]> = None;
    while offset < data.len() {
        if offset + 2 > data.len() {
            return None;
        }
        let tlv_type = data[offset];
        let length = data[offset + 1] as usize;
        offset += 2;
        if offset + length > data.len() {
            return None;
        }
        let value = &data[offset..offset + length];
        match tlv_type {
            0x01 if capabilities.is_none() && !value.is_empty() && length <= 8 => {
                capabilities = PeerCapabilities::decode(value);
            }
            0x02 if signing.is_none() && length == 32 => {
                signing = Some(value.try_into().ok()?);
            }
            _ => {} // unknown TLV: skip (forward compatibility)
        }
        offset += length;
    }
    Some((capabilities?, signing?))
}

impl serde::Serialize for PeerCapabilities {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_u64(self.0)
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
    fn authenticated_peer_state_round_trip() {
        let key = [0xABu8; 32];
        let caps = PeerCapabilities::local_supported();
        let encoded = authenticated_peer_state(caps, &key);
        // Exact wire layout: version, caps TLV, key TLV.
        assert_eq!(encoded[0], 0x01);
        assert_eq!(encoded[1], 0x01);
        let caps_len = encoded[2] as usize;
        assert_ne!(caps_len, 0);
        assert_eq!(encoded[3 + caps_len], 0x02);
        assert_eq!(encoded[4 + caps_len], 32);
        assert_eq!(encoded.len(), 5 + caps_len + 32);

        let (parsed_caps, parsed_key) = parse_authenticated_peer_state(&encoded).unwrap();
        assert_eq!(parsed_caps, caps);
        assert_eq!(parsed_key, key);
    }

    #[test]
    fn authenticated_peer_state_rejects_bad_input() {
        let key = [0xCDu8; 32];
        let good = authenticated_peer_state(PeerCapabilities::local_supported(), &key);
        // Wrong version.
        let mut bad = good.clone();
        bad[0] = 0x02;
        assert!(parse_authenticated_peer_state(&bad).is_none());
        // Truncated TLV.
        assert!(parse_authenticated_peer_state(&good[..good.len() - 5]).is_none());
        // Missing signing key TLV (only caps present).
        let caps = PeerCapabilities::local_supported().encode();
        let mut only_caps = vec![0x01, 0x01, caps.len() as u8];
        only_caps.extend_from_slice(&caps);
        assert!(parse_authenticated_peer_state(&only_caps).is_none());
        // Empty.
        assert!(parse_authenticated_peer_state(&[]).is_none());
    }

    #[test]
    fn authenticated_peer_state_skips_unknown_tlv() {
        let key = [0xEFu8; 32];
        let encoded = authenticated_peer_state(PeerCapabilities(0x21), &key);
        // Splice in an unknown TLV (0x7F, 3 bytes) between the known ones.
        let caps_len = encoded[2] as usize;
        let mut with_unknown = Vec::new();
        with_unknown.extend_from_slice(&encoded[..3 + caps_len]);
        with_unknown.extend_from_slice(&[0x7F, 0x03, 9, 9, 9]);
        with_unknown.extend_from_slice(&encoded[3 + caps_len..]);
        let (caps, parsed_key) = parse_authenticated_peer_state(&with_unknown).unwrap();
        assert_eq!(caps, PeerCapabilities(0x21));
        assert_eq!(parsed_key, key);
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
