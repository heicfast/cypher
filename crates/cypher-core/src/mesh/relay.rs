//! Relay / flood-control policy — the port of bitchat's `RelayController`.
//!
//! Decides whether a received packet should be re-broadcast, with what TTL
//! cap, and with how much jitter. REQUEST_SYNC is never relayed; directed
//! traffic always relays deterministically; broadcasts are clamped by graph
//! density with degree-scaled jitter.

use crate::consts;
use crate::packet::BitchatPacket;
use crate::types::MessageType;

/// Relay decision for a received packet.
#[derive(Clone, Debug, PartialEq)]
pub struct RelayDecision {
    pub should_relay: bool,
    /// TTL the relayed copy carries (already decremented by one hop).
    pub new_ttl: u8,
    /// Jitter bounds in milliseconds (delay drawn uniformly in [min, max]).
    pub jitter_min_ms: u64,
    pub jitter_max_ms: u64,
}

/// Decide the relay fate of `packet` received from `ingress_peer`, with the
/// local degree (connected peer count).
pub fn decide(
    packet: &BitchatPacket,
    ingress_peer: &crate::PeerId,
    local_peer: &crate::PeerId,
    degree: usize,
) -> RelayDecision {
    let no_relay = RelayDecision {
        should_relay: false,
        new_ttl: 0,
        jitter_min_ms: 0,
        jitter_max_ms: 0,
    };

    // REQUEST_SYNC is link-local: never relayed.
    if packet.packet_type == MessageType::RequestSync.as_u8() {
        return no_relay;
    }
    // Nothing to relay at the hop limit, or self/loop traffic.
    let ttl_cap = packet.ttl.min(consts::MESSAGE_TTL);
    if ttl_cap <= 1 {
        return no_relay;
    }
    let sender = crate::PeerId::from_routing_bytes(&packet.sender_id);
    if sender == *local_peer {
        return no_relay;
    }
    // Directed at us → deliver, don't relay.
    if let Some(recipient) = &packet.recipient_id {
        if crate::PeerId::from_routing_bytes(recipient) == *local_peer {
            return no_relay;
        }
    }
    let _ = ingress_peer;

    let dense = degree >= consts::BLE_HIGH_DEGREE_THRESHOLD;
    let packet_type = packet.packet_type;

    // Deterministic class: handshakes and directed encrypted traffic always
    // relay, TTL − 1, tight jitter.
    let is_handshake = packet_type == MessageType::NoiseHandshake.as_u8();
    let is_directed_encrypted = matches!(
        MessageType::from_u8(packet_type),
        Some(MessageType::NoiseEncrypted)
            | Some(MessageType::CourierEnvelope)
            | Some(MessageType::Ping)
            | Some(MessageType::Pong)
    ) && packet.recipient_id.is_some();
    let is_directed_fragment =
        packet_type == MessageType::Fragment.as_u8() && packet.recipient_id.is_some();

    if is_handshake {
        return RelayDecision {
            should_relay: true,
            new_ttl: ttl_cap - 1,
            jitter_min_ms: 10,
            jitter_max_ms: 35,
        };
    }
    if is_directed_encrypted || is_directed_fragment {
        return RelayDecision {
            should_relay: true,
            new_ttl: ttl_cap - 1,
            jitter_min_ms: 20,
            jitter_max_ms: 60,
        };
    }

    // Fragments and voice: dense graphs cap TTL at 5, sparse at 7.
    if packet_type == MessageType::Fragment.as_u8()
        || packet_type == MessageType::VoiceFrame.as_u8()
    {
        let cap = if dense {
            5u8.min(ttl_cap)
        } else {
            7u8.min(ttl_cap)
        };
        if cap <= 1 {
            return no_relay;
        }
        return RelayDecision {
            should_relay: true,
            new_ttl: cap - 1,
            jitter_min_ms: consts::BLE_FRAGMENT_RELAY_MIN_DELAY_MS,
            jitter_max_ms: consts::BLE_FRAGMENT_RELAY_MAX_DELAY_MS,
        };
    }

    // Broadcasts: degree-scaled TTL clamps and jitter.
    let is_announce = packet_type == MessageType::Announce.as_u8();
    let is_urgent_board = packet_type == MessageType::BoardPost.as_u8()
        && crate::protocol::board::BoardWire::urgent_flag(&packet.payload);
    let preferred: u8 = if is_announce || is_urgent_board { 7 } else { 6 };
    let ttl_limit = if dense {
        5u8
    } else if degree <= 2 {
        ttl_cap
    } else {
        preferred
    };
    let clamped = ttl_limit.min(ttl_cap).max(2);
    if clamped <= 1 {
        return no_relay;
    }
    let (jmin, jmax) = match degree {
        0..=2 => (10, 40),
        3..=5 => (60, 150),
        6..=9 => (80, 180),
        _ => (100, 220),
    };
    RelayDecision {
        should_relay: true,
        new_ttl: clamped - 1,
        jitter_min_ms: jmin,
        jitter_max_ms: jmax,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pkt(t: MessageType, ttl: u8, recipient: Option<[u8; 8]>) -> BitchatPacket {
        let mut p = BitchatPacket::new(
            t.as_u8(),
            [0x11; 8],
            recipient,
            1000,
            b"payload".to_vec(),
            ttl,
        );
        p.signature = Some([9; 64]);
        p
    }

    #[test]
    fn request_sync_never_relays() {
        let local = crate::PeerId::new("aa");
        let d = decide(
            &pkt(MessageType::RequestSync, 7, None),
            &local.clone(),
            &local,
            3,
        );
        assert!(!d.should_relay);
    }

    #[test]
    fn handshake_always_relays_ttl_minus_one() {
        let local = crate::PeerId::new("aa");
        let d = decide(
            &pkt(MessageType::NoiseHandshake, 7, Some([0x22; 8])),
            &local.clone(),
            &local,
            3,
        );
        assert!(d.should_relay);
        assert_eq!(d.new_ttl, 6);
        assert_eq!((d.jitter_min_ms, d.jitter_max_ms), (10, 35));
    }

    #[test]
    fn ttl_one_not_relaid() {
        let local = crate::PeerId::new("aa");
        let d = decide(
            &pkt(MessageType::Message, 1, None),
            &local.clone(),
            &local,
            3,
        );
        assert!(!d.should_relay);
    }

    #[test]
    fn self_and_own_traffic_not_relaid() {
        let local = crate::PeerId::new("1111111111111111");
        let mut p = pkt(MessageType::Message, 7, None);
        p.sender_id = local.routing_bytes();
        let d = decide(&p, &local.clone(), &local, 3);
        assert!(!d.should_relay);
    }

    #[test]
    fn directed_to_us_not_relaid() {
        let local = crate::PeerId::new("2222222222222222");
        let mut p = pkt(MessageType::NoiseEncrypted, 7, None);
        p.recipient_id = Some(local.routing_bytes());
        let d = decide(&p, &local.clone(), &local, 3);
        assert!(!d.should_relay);
    }

    #[test]
    fn broadcast_tightened_when_dense() {
        let local = crate::PeerId::new("aa");
        let dense = decide(
            &pkt(MessageType::Message, 7, None),
            &local.clone(),
            &local,
            8,
        );
        assert!(dense.should_relay);
        assert_eq!(dense.new_ttl, 4); // cap 5, minus one hop
        let sparse = decide(
            &pkt(MessageType::Message, 7, None),
            &local.clone(),
            &local,
            1,
        );
        assert!(sparse.should_relay);
        assert_eq!(sparse.new_ttl, 6); // preferred 6, minus one hop
                                       // Announces get announce-class headroom (7 → 6) in mid-density graphs.
        let mid = decide(
            &pkt(MessageType::Announce, 7, None),
            &local.clone(),
            &local,
            4,
        );
        assert_eq!(mid.new_ttl, 6);
    }

    #[test]
    fn fragment_dense_cap_five() {
        let local = crate::PeerId::new("aa");
        let dense = decide(
            &pkt(MessageType::Fragment, 7, None),
            &local.clone(),
            &local,
            9,
        );
        assert_eq!(dense.new_ttl, 4); // cap 5 − 1
        let sparse = decide(
            &pkt(MessageType::Fragment, 7, None),
            &local.clone(),
            &local,
            2,
        );
        assert_eq!(sparse.new_ttl, 6); // cap 7 − 1
    }
}
