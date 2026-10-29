//! Peer identifiers.
//!
//! bitchat models a `PeerID` as a lowercased string with an optional prefix.
//! The on-air identifier is the **short** form: the first 8 bytes of
//! `SHA-256(noise_static_public_key)` hex-encoded (16 chars). The full
//! fingerprint (64 hex chars) doubles as the persistent identity, and the raw
//! 64-hex public key itself is accepted as a full-key PeerID.

use crate::util::{hex_encode, sha256};
use std::fmt;

/// Number of bytes carried on the wire for a peer ID.
pub const PEER_ID_WIRE_BYTES: usize = 8;

/// Prefix namespaces understood by bitchat's `PeerID`. Kept for wire/UI
/// compatibility; the mesh path mostly uses the empty prefix.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum PeerPrefix {
    Raw,
    Mesh,
    Name,
    Noise,
    Group,
    Bridge,
}

impl PeerPrefix {
    pub fn as_str(&self) -> &'static str {
        match self {
            PeerPrefix::Raw => "",
            PeerPrefix::Mesh => "mesh:",
            PeerPrefix::Name => "name:",
            PeerPrefix::Noise => "noise:",
            PeerPrefix::Group => "group_",
            PeerPrefix::Bridge => "bridge:",
        }
    }
}

/// A peer identifier. Canonical, lowercased, prefix + bare form.
#[derive(Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct PeerId {
    id: String,
}

impl PeerId {
    /// Derive the short on-air peer ID from a 32-byte Noise static public key:
    /// `lowercase_hex(SHA-256(key))[0..16]`.
    pub fn from_public_key(public_key: &[u8; 32]) -> Self {
        PeerId {
            id: hex_encode(&sha256(public_key))[..16].to_string(),
        }
    }

    /// Full fingerprint (64 lowercase hex chars of SHA-256 of the key).
    pub fn fingerprint_of(public_key: &[u8; 32]) -> String {
        hex_encode(&sha256(public_key))
    }

    /// Wrap an existing ID string (already canonicalized/lowercased here).
    pub fn new(id: impl Into<String>) -> Self {
        PeerId {
            id: id.into().to_lowercase(),
        }
    }

    /// The full canonical ID string (prefix + bare), as persisted/displayed.
    pub fn as_str(&self) -> &str {
        &self.id
    }

    /// Short (16-hex-char) routing form. If the ID is already short it is
    /// returned as-is; full 64-hex keys and prefixed forms truncate to the
    /// first 16 chars (matching bitchat's `toShort()`).
    pub fn short(&self) -> PeerId {
        let bare = self.bare();
        if bare.len() == 16 {
            return PeerId { id: bare.to_string() };
        }
        PeerId {
            id: bare.chars().take(16).collect(),
        }
    }

    /// Bare form without the prefix.
    pub fn bare(&self) -> &str {
        for p in [PeerPrefix::Mesh, PeerPrefix::Name, PeerPrefix::Noise, PeerPrefix::Group, PeerPrefix::Bridge] {
            if let Some(rest) = self.id.strip_prefix(p.as_str()) {
                return rest;
            }
        }
        &self.id
    }

    /// True when this ID is a bare 64-hex Noise public key.
    pub fn is_full_noise_key(&self) -> bool {
        let bare = self.bare();
        bare.len() == 64 && bare.bytes().all(|b| b.is_ascii_hexdigit())
    }

    /// True when this is the short on-air form (bare 16 hex chars).
    pub fn is_short(&self) -> bool {
        let bare = self.bare();
        bare.len() == 16
            && self.id.starts_with(|c: char| c.is_ascii_hexdigit())
            && bare.bytes().all(|b| b.is_ascii_hexdigit())
    }

    /// The 8 raw bytes carried in packet headers. Longer inputs truncate,
    /// shorter inputs zero-pad — exactly like bitchat's `routingData`.
    pub fn routing_bytes(&self) -> [u8; PEER_ID_WIRE_BYTES] {
        let mut out = [0u8; PEER_ID_WIRE_BYTES];
        let source: Vec<u8> = if let Some(bytes) = crate::util::hex_decode(self.bare()) {
            if bytes.len() == PEER_ID_WIRE_BYTES {
                bytes
            } else {
                self.bare().as_bytes().to_vec()
            }
        } else {
            self.bare().as_bytes().to_vec()
        };
        let n = source.len().min(PEER_ID_WIRE_BYTES);
        out[..n].copy_from_slice(&source[..n]);
        out
    }

    /// Build from 8 raw wire bytes.
    pub fn from_routing_bytes(bytes: &[u8]) -> Self {
        let mut b = [0u8; PEER_ID_WIRE_BYTES];
        let n = bytes.len().min(PEER_ID_WIRE_BYTES);
        b[..n].copy_from_slice(&bytes[..n]);
        PeerId {
            id: hex_encode(&b),
        }
    }

    /// Broadcast recipient marker (`FF` × 8) used on fragment packets.
    pub fn broadcast_marker() -> [u8; PEER_ID_WIRE_BYTES] {
        [0xFF; PEER_ID_WIRE_BYTES]
    }
}

impl fmt::Display for PeerId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.id)
    }
}

impl fmt::Debug for PeerId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "PeerId({})", self.id)
    }
}

/// Sanitize an arbitrary byte slice to exactly 8 bytes (truncate/zero-pad).
/// Used for sender/recipient IDs and route hops on encode.
pub fn sanitize_wire_id(bytes: &[u8]) -> [u8; PEER_ID_WIRE_BYTES] {
    let mut out = [0u8; PEER_ID_WIRE_BYTES];
    let n = bytes.len().min(PEER_ID_WIRE_BYTES);
    out[..n].copy_from_slice(&bytes[..n]);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn short_id_derivation() {
        let key = [0x42u8; 32];
        let fp = PeerId::fingerprint_of(&key);
        assert_eq!(fp.len(), 64);
        let id = PeerId::from_public_key(&key);
        assert_eq!(id.as_str(), &fp[..16]);
        assert_eq!(id.routing_bytes(), {
            let mut b = [0u8; 8];
            b.copy_from_slice(&crate::util::hex_decode(&fp[..16]).unwrap());
            b
        });
    }

    #[test]
    fn prefix_forms() {
        let g = PeerId::new("GROUP_ABCDEF0123456789ABCDEF0123456789");
        assert_eq!(g.bare(), "abcdef0123456789abcdef0123456789");
        let n = PeerId::new("noise:");
        let _ = n;
        let full = PeerId::new(
            "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf",
        );
        assert!(full.is_full_noise_key());
        assert_eq!(full.short().as_str(), "a0a1a2a3a4a5a6a7");
    }

    #[test]
    fn wire_round_trip() {
        let id = PeerId::new("0011223344556677");
        assert_eq!(PeerId::from_routing_bytes(&id.routing_bytes()), id);
    }
}
