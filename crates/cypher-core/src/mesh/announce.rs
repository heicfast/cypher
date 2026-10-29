//! Announce handling: trust policy (TOFU + key pinning), preflight
//! validation, throttling, and the maintenance announce cadence.

use crate::consts;
use crate::protocol::announce::AnnouncementPacket;
use crate::protocol::capabilities::PeerCapabilities;
use crate::types::MessageType;

/// Why an announce was rejected.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum AnnounceRejection {
    /// Payload did not decode.
    Malformed,
    /// Derived peer ID ≠ packet sender.
    IdentityMismatch,
    /// Self-announce.
    SelfAnnounce,
    /// Older than the 900 s freshness window.
    Stale,
    /// The Noise static key changed for a known peer.
    KeyMismatch,
    /// The signing key changed against a pinned (TOFU) key.
    SigningKeyMismatch,
    /// Signature absent or invalid.
    InvalidSignature,
}

#[derive(Clone, Debug, PartialEq)]
pub struct AcceptedAnnounce {
    pub packet: AnnouncementPacket,
    pub peer_id: crate::PeerId,
    /// `ttl == 7` ⇒ direct (one-hop) announce.
    pub is_direct: bool,
}

/// Preflight + trust evaluation for an announce packet.
pub fn evaluate(
    packet: &crate::packet::BitchatPacket,
    known_noise_key: Option<[u8; 32]>,
    pinned_signing_key: Option<[u8; 32]>,
    authenticated_signing_key: Option<[u8; 32]>,
    local_peer: &crate::PeerId,
    now_ms: u64,
) -> Result<AcceptedAnnounce, AnnounceRejection> {
    if packet.packet_type != MessageType::Announce.as_u8() {
        return Err(AnnounceRejection::Malformed);
    }
    let announcement =
        AnnouncementPacket::decode(&packet.payload).ok_or(AnnounceRejection::Malformed)?;

    // Derived peer ID must equal the packet's claimed sender.
    let Ok(noise_key): Result<[u8; 32], _> = announcement.noise_public_key.clone().try_into()
    else {
        return Err(AnnounceRejection::Malformed);
    };
    let derived = crate::PeerId::from_public_key(&noise_key);
    if derived.as_str() != crate::util::hex_encode(&packet.sender_id) {
        return Err(AnnounceRejection::IdentityMismatch);
    }
    if derived == *local_peer {
        return Err(AnnounceRejection::SelfAnnounce);
    }

    // Freshness: 900 s window.
    if now_ms.saturating_sub(packet.timestamp) > consts::BLE_ANNOUNCE_MAX_AGE_S * 1000 {
        return Err(AnnounceRejection::Stale);
    }

    // Trust ladder: known keys must not change.
    if let Some(known) = known_noise_key {
        if known != noise_key {
            return Err(AnnounceRejection::KeyMismatch);
        }
    }
    let Ok(signing_key): Result<[u8; 32], _> = announcement.signing_public_key.clone().try_into()
    else {
        return Err(AnnounceRejection::Malformed);
    };
    if let Some(authenticated) = authenticated_signing_key {
        if authenticated != signing_key {
            return Err(AnnounceRejection::SigningKeyMismatch);
        }
    } else if let Some(pinned) = pinned_signing_key {
        if pinned != signing_key {
            return Err(AnnounceRejection::SigningKeyMismatch);
        }
    }

    // The announce must carry a valid packet-level Ed25519 signature.
    let Some(signature) = packet.signature else {
        return Err(AnnounceRejection::InvalidSignature);
    };
    let Some(preimage) = packet.to_binary_data_for_signing() else {
        return Err(AnnounceRejection::InvalidSignature);
    };
    if !crate::identity::CypherIdentity::verify(&signature, &preimage, &signing_key) {
        return Err(AnnounceRejection::InvalidSignature);
    }

    Ok(AcceptedAnnounce {
        packet: announcement,
        peer_id: derived,
        is_direct: packet.ttl == consts::MESSAGE_TTL,
    })
}

/// Forced/natural announce send throttling.
#[derive(Default)]
pub struct AnnounceThrottle {
    last_sent_ms: Option<u64>,
    last_forced_ms: Option<u64>,
}

impl AnnounceThrottle {
    /// May we send an announce now? Forced sends (subscribe events, new
    /// peers) are bounded at 0.15 s; natural sends at 1.0 s.
    pub fn may_send(&self, forced: bool, now_ms: u64) -> bool {
        if forced {
            match self.last_forced_ms {
                Some(last) => {
                    now_ms.saturating_sub(last)
                        >= (consts::BLE_FORCE_ANNOUNCE_MIN_INTERVAL_S * 1000.0) as u64
                }
                None => true,
            }
        } else {
            match self.last_sent_ms {
                Some(last) => {
                    now_ms.saturating_sub(last)
                        >= (consts::BLE_ANNOUNCE_MIN_INTERVAL_S * 1000.0) as u64
                }
                None => true,
            }
        }
    }

    pub fn record(&mut self, forced: bool, now_ms: u64) {
        if forced {
            self.last_forced_ms = Some(now_ms);
        }
        self.last_sent_ms = Some(now_ms);
    }

    pub fn reset(&mut self) {
        self.last_sent_ms = None;
        self.last_forced_ms = None;
    }
}

/// Maintenance-cycle announce decision (bitchat's `BLEMaintenancePolicy`).
pub fn should_announce(
    cycle: u64,
    connected_count: usize,
    recent_traffic: bool,
    elapsed_since_last_s: f64,
) -> bool {
    if connected_count == 0 {
        // Isolated: announce every 4 s.
        return elapsed_since_last_s >= consts::BLE_ANNOUNCE_INTERVAL_ISOLATED_S;
    }
    if recent_traffic {
        // Active conversation: slow cadence.
        return elapsed_since_last_s >= 10.0;
    }
    let (base, jitter) = if connected_count >= consts::BLE_HIGH_DEGREE_THRESHOLD {
        (
            consts::BLE_ANNOUNCE_INTERVAL_CONNECTED_DENSE_S,
            consts::BLE_ANNOUNCE_JITTER_DENSE_S,
        )
    } else {
        (
            consts::BLE_ANNOUNCE_INTERVAL_CONNECTED_SPARSE_S,
            consts::BLE_ANNOUNCE_JITTER_SPARSE_S,
        )
    };
    let _ = cycle;
    elapsed_since_last_s >= base - jitter
}

/// Build the outbound announce payload for the local node.
pub fn build_payload(
    nickname: &str,
    noise_public_key: &[u8; 32],
    signing_public_key: &[u8; 32],
    neighbors: &[[u8; 8]],
    capabilities: PeerCapabilities,
) -> Option<Vec<u8>> {
    let packet = AnnouncementPacket {
        nickname: nickname.to_string(),
        noise_public_key: noise_public_key.to_vec(),
        signing_public_key: signing_public_key.to_vec(),
        direct_neighbors: if neighbors.is_empty() {
            None
        } else {
            Some(neighbors.to_vec())
        },
        capabilities: Some(capabilities),
        bridge_geohash: None,
    };
    packet.encode()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::packet::BitchatPacket;

    fn signed_announce(
        identity: &crate::identity::CypherIdentity,
        nickname: &str,
        ttl: u8,
        now_ms: u64,
    ) -> BitchatPacket {
        let payload = build_payload(
            nickname,
            &identity.noise_static_public(),
            &identity.signing_public(),
            &[],
            PeerCapabilities::local_supported(),
        )
        .unwrap();
        let mut packet = BitchatPacket::new(
            MessageType::Announce.as_u8(),
            identity.peer_id().routing_bytes(),
            None,
            now_ms,
            payload,
            ttl,
        );
        let preimage = packet.to_binary_data_for_signing().unwrap();
        packet.signature = Some(identity.sign(&preimage));
        packet
    }

    #[test]
    fn valid_announce_accepted() {
        let alice = crate::identity::CypherIdentity::generate();
        let local = crate::PeerId::new("ffffffffffffffff");
        let packet = signed_announce(&alice, "alice", consts::MESSAGE_TTL, 1000);
        let result = evaluate(&packet, None, None, None, &local, 2000);
        assert!(result.is_ok());
        let accepted = result.unwrap();
        assert_eq!(accepted.peer_id, alice.peer_id());
        assert!(accepted.is_direct);
        assert_eq!(accepted.packet.nickname, "alice");
    }

    #[test]
    fn identity_mismatch_rejected() {
        let alice = crate::identity::CypherIdentity::generate();
        let local = crate::PeerId::new("ffffffffffffffff");
        let mut packet = signed_announce(&alice, "alice", 7, 1000);
        packet.sender_id = [0x99; 8]; // claim a different sender
        assert_eq!(
            evaluate(&packet, None, None, None, &local, 2000),
            Err(AnnounceRejection::IdentityMismatch)
        );
    }

    #[test]
    fn stale_rejected() {
        let alice = crate::identity::CypherIdentity::generate();
        let local = crate::PeerId::new("ffffffffffffffff");
        let packet = signed_announce(&alice, "alice", 7, 0);
        let now = consts::BLE_ANNOUNCE_MAX_AGE_S * 1000 + 1;
        assert_eq!(
            evaluate(&packet, None, None, None, &local, now),
            Err(AnnounceRejection::Stale)
        );
    }

    #[test]
    fn key_mismatch_and_signature_forgery_rejected() {
        let alice = crate::identity::CypherIdentity::generate();
        let local = crate::PeerId::new("ffffffffffffffff");
        let packet = signed_announce(&alice, "alice", 7, 1000);

        // Known noise key that differs.
        assert_eq!(
            evaluate(&packet, Some([0x00; 32]), None, None, &local, 2000),
            Err(AnnounceRejection::KeyMismatch)
        );
        // TOFU-pinned signing key that differs.
        assert_eq!(
            evaluate(&packet, None, Some([0x11; 32]), None, &local, 2000),
            Err(AnnounceRejection::SigningKeyMismatch)
        );
        // Tampered nickname (still valid UTF-8) keeps the structure and the
        // claimed identity intact but invalidates the signature. (Tampering
        // the keys would read as IdentityMismatch/Malformed first, since the
        // identity check precedes signature verification.)
        let mut tampered = packet.clone();
        tampered.payload[4] = b'L'; // 'a' -> 'L' in "alice"
        assert_eq!(
            evaluate(&tampered, None, None, None, &local, 2000),
            Err(AnnounceRejection::InvalidSignature)
        );
    }

    #[test]
    fn relayed_announce_not_direct() {
        let alice = crate::identity::CypherIdentity::generate();
        let local = crate::PeerId::new("ffffffffffffffff");
        let packet = signed_announce(&alice, "alice", 5, 1000); // TTL decremented by a relay
        let accepted = evaluate(&packet, None, None, None, &local, 2000).unwrap();
        assert!(!accepted.is_direct);
    }

    #[test]
    fn throttle_windows() {
        let mut t = AnnounceThrottle::default();
        assert!(t.may_send(false, 0));
        t.record(false, 0);
        assert!(!t.may_send(false, 500));
        assert!(t.may_send(false, 1001));
        // Forced sends are tighter.
        assert!(t.may_send(true, 1002));
        t.record(true, 1002);
        assert!(!t.may_send(true, 1100));
        assert!(t.may_send(true, 1152));
        t.reset();
        assert!(t.may_send(false, 1200));
    }

    #[test]
    fn maintenance_cadence() {
        // Isolated: 4 s.
        assert!(should_announce(0, 0, false, 4.1));
        assert!(!should_announce(0, 0, false, 3.9));
        // Sparse connected: 15 s ± 4 s jitter.
        assert!(should_announce(3, 2, false, 11.5));
        assert!(!should_announce(3, 2, false, 10.5));
        // Dense: 30 ± 8.
        assert!(should_announce(3, 7, false, 22.5));
        assert!(!should_announce(3, 7, false, 21.5));
        // With traffic: 10 s regardless.
        assert!(should_announce(3, 7, true, 10.1));
    }
}
