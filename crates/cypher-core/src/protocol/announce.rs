//! Announce packets: identity announcements (TLV, 1-byte lengths) and the
//! in-session authenticated peer state.

use crate::protocol::capabilities::PeerCapabilities;
use crate::tlv::{write_short, TlvReader};

/// `announce` (0x01) payload — TLV with 1-byte lengths:
/// `0x01 nickname`, `0x02 noisePublicKey(32)`, `0x03 signingPublicKey(32)`,
/// `0x04 directNeighbors (N×8, ≤10)`, `0x05 capabilities (LE minimal)`,
/// `0x06 bridgeGeohash (≤12)`. Unknown TLVs skipped; nickname + both keys
/// required.
#[derive(Clone, Debug, PartialEq)]
pub struct AnnouncementPacket {
    pub nickname: String,
    pub noise_public_key: Vec<u8>,
    pub signing_public_key: Vec<u8>,
    pub direct_neighbors: Option<Vec<[u8; 8]>>,
    pub capabilities: Option<PeerCapabilities>,
    pub bridge_geohash: Option<String>,
}

pub const MAX_NEIGHBORS: usize = 10;

impl AnnouncementPacket {
    pub fn encode(&self) -> Option<Vec<u8>> {
        let nickname = self.nickname.as_bytes();
        if nickname.len() > 255 {
            return None;
        }
        if self.noise_public_key.len() > 255 || self.signing_public_key.len() > 255 {
            return None;
        }
        let mut data = Vec::with_capacity(2 + nickname.len() + 2 + self.noise_public_key.len() + 2 + self.signing_public_key.len());
        write_short(&mut data, 0x01, nickname);
        write_short(&mut data, 0x02, &self.noise_public_key);
        write_short(&mut data, 0x03, &self.signing_public_key);

        // Neighbors: first 10, concatenated 8-byte IDs; silently dropped when
        // a group is not a multiple of 8 (matches bitchat).
        if let Some(neighbors) = &self.direct_neighbors {
            if !neighbors.is_empty() {
                let taken: Vec<&[u8; 8]> = neighbors.iter().take(MAX_NEIGHBORS).collect();
                let total: usize = taken.iter().map(|n| n.len()).sum();
                if !taken.is_empty() && total.is_multiple_of(8) {
                    let mut blob = Vec::with_capacity(total);
                    for n in taken {
                        blob.extend_from_slice(n);
                    }
                    write_short(&mut data, 0x04, &blob);
                }
            }
        }

        if let Some(caps) = self.capabilities {
            let encoded = caps.encode();
            if encoded.len() > 255 {
                return None;
            }
            write_short(&mut data, 0x05, &encoded);
        }

        if let Some(geohash) = &self.bridge_geohash {
            let cell = geohash.as_bytes();
            if !cell.is_empty() && cell.len() <= 12 {
                write_short(&mut data, 0x06, cell);
            }
        }

        Some(data)
    }

    pub fn decode(data: &[u8]) -> Option<Self> {
        let mut nickname: Option<String> = None;
        let mut noise_public_key: Option<Vec<u8>> = None;
        let mut signing_public_key: Option<Vec<u8>> = None;
        let mut direct_neighbors: Option<Vec<[u8; 8]>> = None;
        let mut capabilities: Option<PeerCapabilities> = None;
        let mut bridge_geohash: Option<String> = None;

        let mut reader = TlvReader::short(data);
        while let Some((tag, value)) = reader.next_record() {
            match tag {
                0x01 => nickname = String::from_utf8(value.to_vec()).ok(),
                0x02 => noise_public_key = Some(value.to_vec()),
                0x03 => signing_public_key = Some(value.to_vec()),
                0x04 => {
                    if !value.is_empty() && value.len().is_multiple_of(8) {
                        let neighbors: Vec<[u8; 8]> = value
                            .chunks_exact(8)
                            .map(|c| c.try_into().unwrap())
                            .collect();
                        direct_neighbors = Some(neighbors);
                    }
                }
                0x05 => capabilities = PeerCapabilities::decode(value),
                0x06 => {
                    if value.len() <= 12 {
                        bridge_geohash = String::from_utf8(value.to_vec()).ok();
                    }
                }
                _ => {} // skip unknown
            }
        }

        Some(AnnouncementPacket {
            nickname: nickname?,
            noise_public_key: noise_public_key?,
            signing_public_key: signing_public_key?,
            direct_neighbors,
            capabilities,
            bridge_geohash,
        })
    }
}

/// `authenticatedPeerState` (Noise payload 0x21) — capabilities + signing
/// key re-asserted **inside** an established Noise session, where possession
/// of the keys is actually proven.
///
/// Wire format: `[version=0x01]` then TLVs `0x01 capabilities (canonical
/// minimal LE, 1–8 B)` and `0x02 signingPublicKey (32 B)`. Rejects unknown
/// versions, duplicates, and non-canonical capability encodings.
#[derive(Clone, Debug, PartialEq)]
pub struct AuthenticatedPeerStatePacket {
    pub capabilities: PeerCapabilities,
    pub signing_public_key: Vec<u8>,
}

pub const AUTH_PEER_STATE_VERSION: u8 = 0x01;
pub const SIGNING_KEY_LENGTH: usize = 32;

impl AuthenticatedPeerStatePacket {
    pub fn encode(&self) -> Option<Vec<u8>> {
        if self.signing_public_key.len() != SIGNING_KEY_LENGTH {
            return None;
        }
        let caps = self.capabilities.encode();
        if caps.is_empty() || caps.len() > 8 {
            return None;
        }
        let mut data = Vec::with_capacity(1 + 2 + caps.len() + 2 + 32);
        data.push(AUTH_PEER_STATE_VERSION);
        write_short(&mut data, 0x01, &caps);
        write_short(&mut data, 0x02, &self.signing_public_key);
        Some(data)
    }

    pub fn decode(data: &[u8]) -> Option<Self> {
        let (&version, rest) = data.split_first()?;
        if version != AUTH_PEER_STATE_VERSION {
            return None;
        }
        let mut capabilities: Option<PeerCapabilities> = None;
        let mut signing_public_key: Option<Vec<u8>> = None;

        let mut reader = TlvReader::short(rest);
        while let Some((tag, value)) = reader.next_record() {
            match tag {
                0x01 => {
                    if capabilities.is_some() || value.is_empty() || value.len() > 8 {
                        return None; // duplicate / invalid
                    }
                    // Canonical check: re-encoding must be byte-identical.
                    let caps = PeerCapabilities::decode(value)?;
                    if caps.encode() != value {
                        return None;
                    }
                    capabilities = Some(caps);
                }
                0x02 => {
                    if signing_public_key.is_some() || value.len() != SIGNING_KEY_LENGTH {
                        return None;
                    }
                    signing_public_key = Some(value.to_vec());
                }
                _ => {} // skip unknown TLVs
            }
        }

        Some(AuthenticatedPeerStatePacket {
            capabilities: capabilities?,
            signing_public_key: signing_public_key?,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> AnnouncementPacket {
        AnnouncementPacket {
            nickname: "alice".into(),
            noise_public_key: vec![0xAA; 32],
            signing_public_key: vec![0xBB; 32],
            direct_neighbors: Some(vec![[1; 8], [2; 8]]),
            capabilities: Some(PeerCapabilities::local_supported()),
            bridge_geohash: Some("dr5rsj7".into()),
        }
    }

    #[test]
    fn announce_round_trip() {
        let pkt = sample();
        let encoded = pkt.encode().unwrap();
        let decoded = AnnouncementPacket::decode(&encoded).unwrap();
        assert_eq!(decoded, pkt);
    }

    #[test]
    fn neighbors_capped_at_ten() {
        let mut pkt = sample();
        pkt.direct_neighbors = Some((0..12u8).map(|i| [i; 8]).collect());
        let encoded = pkt.encode().unwrap();
        let decoded = AnnouncementPacket::decode(&encoded).unwrap();
        assert_eq!(decoded.direct_neighbors.as_ref().unwrap().len(), 10);
        assert_eq!(decoded.direct_neighbors.unwrap()[0], [0; 8]);
    }

    #[test]
    fn invalid_neighbor_group_dropped_silently() {
        // 7-byte neighbor blob: encode drops the TLV entirely.
        let mut pkt = sample();
        pkt.direct_neighbors = Some(vec![[9; 8]]);
        let mut good = pkt.encode().unwrap();
        // Manually craft a 7-byte neighbors TLV.
        let mut bad = Vec::new();
        bad.push(0x04);
        bad.push(7);
        bad.extend_from_slice(&[0u8; 7]);
        good.extend_from_slice(&bad);
        let decoded = AnnouncementPacket::decode(&good).unwrap();
        // The valid group survives; the invalid one is ignored.
        assert_eq!(decoded.direct_neighbors.unwrap().len(), 1);
    }

    #[test]
    fn unknown_tlv_skipped() {
        let pkt = sample();
        let mut encoded = pkt.encode().unwrap();
        write_short(&mut encoded, 0xFF, b"future extension");
        let decoded = AnnouncementPacket::decode(&encoded).unwrap();
        assert_eq!(decoded.nickname, "alice");
    }

    #[test]
    fn missing_required_rejected() {
        let pkt = sample();
        let encoded = pkt.encode().unwrap();
        // Strip the signing key TLV (type 0x03, len 32 = 34 bytes).
        let cut = encoded.len() - 34;
        assert!(AnnouncementPacket::decode(&encoded[..cut]).is_none());
        assert!(AnnouncementPacket::decode(&[]).is_none());
    }

    #[test]
    fn oversized_nickname_rejected() {
        let mut pkt = sample();
        pkt.nickname = "x".repeat(256);
        assert!(pkt.encode().is_none());
    }

    #[test]
    fn auth_peer_state_round_trip_and_canonical() {
        // bitchat's canonical-prefix vector uses caps = vouch | privateMedia
        // (0x120 → LE [0x20, 0x01]).
        let pkt = AuthenticatedPeerStatePacket {
            capabilities: PeerCapabilities(0x120),
            signing_public_key: vec![0x11; 32],
        };
        let encoded = pkt.encode().unwrap();
        // [version 1][TLV 0x01][len 2][0x20 0x01][TLV 0x02][len 0x20]
        assert_eq!(&encoded[..5], &[0x01, 0x01, 0x02, 0x20, 0x01]);
        assert_eq!(encoded[5], 0x02);
        assert_eq!(encoded[6], 0x20);
        let decoded = AuthenticatedPeerStatePacket::decode(&encoded).unwrap();
        assert_eq!(decoded, pkt);

        // Non-minimal capabilities ([0x01, 0x00] for value 1) rejected.
        let mut bad = vec![0x01u8, 0x01, 0x02, 0x01, 0x00];
        bad.extend_from_slice(&[0x02, 0x20]);
        bad.extend_from_slice(&[0x11; 32]);
        assert!(AuthenticatedPeerStatePacket::decode(&bad).is_none());

        // Unknown version rejected.
        let mut v2 = encoded.clone();
        v2[0] = 0x02;
        assert!(AuthenticatedPeerStatePacket::decode(&v2).is_none());
    }
}
