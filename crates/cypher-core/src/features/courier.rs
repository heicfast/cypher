//! Courier mail: Noise X sealing, envelope carriage, and the sender outbox.

use crate::noise::handshake::{HandshakeState, NoisePattern, NoiseRole};
use crate::noise::NoiseError;
use crate::protocol::courier::CourierEnvelope;
use x25519_dalek::StaticSecret;

pub const COURIER_PROLOGUE: &[u8] = b"bitchat-courier-v1";

/// The inner plaintext of a couriered message: the privateMessage typed
/// payload (`0x01 ‖ PrivateMessagePacket TLV`).
pub fn courier_plaintext(message_id: &str, content: &str) -> Option<Vec<u8>> {
    let pm = crate::protocol::private::PrivateMessagePacket {
        message_id: message_id.to_string(),
        content: content.to_string(),
    }
    .encode()?;
    let mut out = vec![crate::types::NoisePayloadType::PrivateMessage.as_u8()];
    out.extend_from_slice(&pm);
    Some(out)
}

/// Seal a payload to the recipient's static key (v1, no forward secrecy).
pub fn seal_courier_payload(
    payload: &[u8],
    sender_static: &StaticSecret,
    recipient_static: &[u8; 32],
) -> Result<Vec<u8>, NoiseError> {
    let mut seal = HandshakeState::new(
        NoiseRole::Initiator,
        NoisePattern::X,
        Some(clone(sender_static)),
        Some(*recipient_static),
        COURIER_PROLOGUE,
    );
    seal.write_message(payload)
}

/// Seal a payload to a one-time prekey (v2, forward secret). The prologue
/// binds the prekey ID.
pub fn seal_prekey_payload(
    payload: &[u8],
    sender_static: &StaticSecret,
    prekey_id: u32,
    prekey_public: &[u8; 32],
) -> Result<Vec<u8>, NoiseError> {
    let mut prologue = COURIER_PROLOGUE_V2.to_vec();
    prologue.extend_from_slice(&prekey_id.to_be_bytes());
    let mut seal = HandshakeState::new(
        NoiseRole::Initiator,
        NoisePattern::X,
        Some(clone(sender_static)),
        Some(*prekey_public),
        &prologue,
    );
    seal.write_message(payload)
}

/// The prekey seal prologue: `"bitchat-prekey-v1" ‖ u32_be(prekeyID)`.
pub const COURIER_PROLOGUE_V2: &[u8] = b"bitchat-prekey-v1";

/// Open a v1 static-key seal. Returns `(payload, sender_static_key)`.
pub fn open_courier_payload(
    ciphertext: &[u8],
    recipient_static: &StaticSecret,
) -> Result<(Vec<u8>, [u8; 32]), NoiseError> {
    let mut open = HandshakeState::new(
        NoiseRole::Responder,
        NoisePattern::X,
        Some(clone(recipient_static)),
        None,
        COURIER_PROLOGUE,
    );
    let payload = open.read_message(ciphertext)?;
    let sender = open.get_remote_static_public_key().ok_or(NoiseError::MissingKeys)?;
    Ok((payload, sender))
}

/// Open a v2 prekey seal with a specific prekey. Returns
/// `(payload, sender_static_key)`.
pub fn open_prekey_payload(
    ciphertext: &[u8],
    prekey_id: u32,
    prekey_secret: &StaticSecret,
) -> Result<(Vec<u8>, [u8; 32]), NoiseError> {
    let mut prologue = COURIER_PROLOGUE_V2.to_vec();
    prologue.extend_from_slice(&prekey_id.to_be_bytes());
    let mut open = HandshakeState::new(
        NoiseRole::Responder,
        NoisePattern::X,
        Some(clone(prekey_secret)),
        None,
        &prologue,
    );
    let payload = open.read_message(ciphertext)?;
    let sender = open.get_remote_static_public_key().ok_or(NoiseError::MissingKeys)?;
    Ok((payload, sender))
}

/// Build a sealed, depositable envelope addressed by rotating recipient tag.
pub fn make_envelope(
    recipient_noise_static: &[u8; 32],
    sender_static: &StaticSecret,
    message_id: &str,
    content: &str,
    now_ms: u64,
    unix_seconds: u64,
    prekey: Option<(u32, [u8; 32])>,
) -> Option<CourierEnvelope> {
    let payload = courier_plaintext(message_id, content)?;
    let (ciphertext, prekey_id) = match prekey {
        Some((id, key)) => (seal_prekey_payload(&payload, sender_static, id, &key).ok()?, Some(id)),
        None => (seal_courier_payload(&payload, sender_static, recipient_noise_static).ok()?, None),
    };
    let tag = CourierEnvelope::recipient_tag(recipient_noise_static, CourierEnvelope::epoch_day(unix_seconds));
    Some(CourierEnvelope::new(
        tag.to_vec(),
        now_ms + crate::consts::COURIER_MAX_LIFETIME_S * 1000,
        ciphertext,
        crate::consts::COURIER_INITIAL_COPIES,
        prekey_id,
    ))
}

fn clone(secret: &StaticSecret) -> StaticSecret {
    StaticSecret::from(secret.to_bytes())
}

// ---------------------------------------------------------------------------
// CourierStore — envelopes carried for third parties
// ---------------------------------------------------------------------------

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Deposit trust tier.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum CourierTier {
    Favorite,
    Verified,
}

/// An envelope held for carriage (opaque — the courier never learns sender,
/// recipient, or content).
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct StoredEnvelope {
    pub recipient_tag: Vec<u8>,
    pub expiry: u64,
    pub ciphertext: Vec<u8>,
    pub depositor_noise_key: Vec<u8>,
    pub stored_at: u64,
    pub tier: CourierTier,
    pub copies: u8,
    pub sprayed_to: Vec<Vec<u8>>,
    pub last_remote_handover_at: Option<u64>,
    pub prekey_id: Option<u32>,
}

/// The carrying store with per-depositor quotas and tier-based eviction.
#[derive(Default)]
pub struct CourierStore {
    envelopes: Vec<StoredEnvelope>,
}

impl CourierStore {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn len(&self) -> usize {
        self.envelopes.len()
    }

    pub fn is_empty(&self) -> bool {
        self.envelopes.is_empty()
    }

    /// Deposit an envelope from `depositor` (identified by Noise public key).
    /// Validates shape, expiry bounds, quotas, and dedups identical
    /// ciphertext (never replenishing spent spray budgets).
    pub fn deposit(&mut self, envelope: CourierEnvelope, depositor_noise_key: Vec<u8>, tier: CourierTier, now_ms: u64) -> bool {
        if envelope.recipient_tag.len() != crate::consts::COURIER_TAG_LENGTH {
            return false;
        }
        if envelope.ciphertext.is_empty() || envelope.ciphertext.len() > crate::consts::COURIER_MAX_CIPHERTEXT_BYTES {
            return false;
        }
        // Expiry bound: not expired, not pinned beyond 24 h (+1 h slack).
        if envelope.is_expired(now_ms) {
            return false;
        }
        if envelope.expiry > now_ms + (crate::consts::COURIER_MAX_LIFETIME_S + crate::consts::COURIER_EXPIRY_SLACK_S) * 1000 {
            return false;
        }

        // Dedup by identical ciphertext: keep the larger unsprayed budget.
        if let Some(existing) = self
            .envelopes
            .iter_mut()
            .find(|e| e.ciphertext == envelope.ciphertext)
        {
            if existing.copies < envelope.copies && existing.sprayed_to.is_empty() {
                existing.copies = envelope.copies;
            }
            return true;
        }

        // Per-depositor quota by tier.
        let (max_per_depositor, cap, verified_cap) = match tier {
            CourierTier::Favorite => (
                crate::consts::COURIER_MAX_PER_FAVORITE_DEPOSITOR,
                crate::consts::COURIER_STORE_MAX,
                crate::consts::COURIER_STORE_MAX_VERIFIED,
            ),
            CourierTier::Verified => (
                crate::consts::COURIER_MAX_PER_VERIFIED_DEPOSITOR,
                crate::consts::COURIER_STORE_MAX,
                crate::consts::COURIER_STORE_MAX_VERIFIED,
            ),
        };
        let depositor_count = self
            .envelopes
            .iter()
            .filter(|e| e.depositor_noise_key == depositor_noise_key)
            .count();
        if depositor_count >= max_per_depositor {
            return false;
        }

        // Capacity: overflow evicts the oldest VERIFIED mail first, never
        // displacing favorites.
        if self.envelopes.len() >= cap {
            let victim = self
                .envelopes
                .iter()
                .enumerate()
                .filter(|(_, e)| e.tier == CourierTier::Verified)
                .min_by_key(|(i, e)| (e.stored_at, *i))
                .map(|(i, _)| i);
            match victim {
                Some(index) => {
                    self.envelopes.remove(index);
                }
                None => return false, // favorites fill the store
            }
        }
        // Hard verified-tier bound.
        if tier == CourierTier::Verified
            && self.envelopes.iter().filter(|e| e.tier == CourierTier::Verified).count() >= verified_cap
        {
            return false;
        }

        self.envelopes.push(StoredEnvelope {
            recipient_tag: envelope.recipient_tag,
            expiry: envelope.expiry,
            ciphertext: envelope.ciphertext,
            depositor_noise_key,
            stored_at: now_ms,
            tier,
            copies: envelope.copies,
            sprayed_to: Vec::new(),
            last_remote_handover_at: None,
            prekey_id: envelope.prekey_id,
        });
        true
    }

    /// Envelopes addressed to any of `candidate_tags`, removed for handover.
    pub fn take_for_handover(&mut self, candidate_tags: &[[u8; 16]]) -> Vec<StoredEnvelope> {
        let mut taken = Vec::new();
        let mut retained = Vec::new();
        for envelope in self.envelopes.drain(..) {
            if candidate_tags.iter().any(|t| t.as_slice() == envelope.recipient_tag.as_slice()) {
                taken.push(envelope);
            } else {
                retained.push(envelope);
            }
        }
        self.envelopes = retained;
        taken
    }

    /// Envelopes eligible for remote handover (relayed announce), with a
    /// per-envelope cooldown; copies leave with `copies = 1`.
    pub fn envelopes_for_remote_handover(&mut self, now_ms: u64) -> Vec<CourierEnvelope> {
        let cooldown = crate::consts::COURIER_REMOTE_HANDOVER_COOLDOWN_S * 1000;
        let mut out = Vec::new();
        for envelope in &mut self.envelopes {
            let eligible = match envelope.last_remote_handover_at {
                Some(last) => now_ms.saturating_sub(last) >= cooldown,
                None => true,
            };
            if eligible {
                envelope.last_remote_handover_at = Some(now_ms);
                out.push(CourierEnvelope::new(
                    envelope.recipient_tag.clone(),
                    envelope.expiry,
                    envelope.ciphertext.clone(),
                    1,
                    envelope.prekey_id,
                ));
            }
        }
        out
    }

    /// Binary spray: hand `copies/2` to an encountered courier, skipping
    /// envelopes they deposited, carry-only (copies == 1), or already sprayed
    /// to them. Returns the envelopes to hand over; budgets commit here.
    pub fn transfer_spray_copies(&mut self, courier_noise_key: &[u8]) -> Vec<CourierEnvelope> {
        let mut out = Vec::new();
        for envelope in &mut self.envelopes {
            if envelope.copies <= 1
                || envelope.depositor_noise_key == courier_noise_key
                || envelope.sprayed_to.iter().any(|k| k == courier_noise_key)
            {
                continue;
            }
            let give = envelope.copies / 2;
            if give == 0 {
                continue;
            }
            envelope.copies -= give;
            envelope.sprayed_to.push(courier_noise_key.to_vec());
            out.push(CourierEnvelope::new(
                envelope.recipient_tag.clone(),
                envelope.expiry,
                envelope.ciphertext.clone(),
                give,
                envelope.prekey_id,
            ));
        }
        out
    }

    /// Drop expired envelopes.
    pub fn remove_expired(&mut self, now_ms: u64) {
        self.envelopes.retain(|e| now_ms < e.expiry);
    }

    pub fn carried_count(&self) -> usize {
        self.envelopes.len()
    }
}

// ---------------------------------------------------------------------------
// MessageOutboxStore — the sender's queue of undelivered DMs
// ---------------------------------------------------------------------------

/// A queued private message awaiting transport.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct QueuedMessage {
    pub content: String,
    pub nickname: String,
    pub message_id: String,
    pub timestamp_ms: u64,
    pub send_attempts: usize,
    pub deposited_courier_keys: Vec<String>,
}

/// Outbox state per peer (in-memory; the host persists it sealed).
#[derive(Default)]
pub struct MessageOutboxStore {
    outbox: HashMap<String, Vec<QueuedMessage>>,
}

impl MessageOutboxStore {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn enqueue(&mut self, peer: &crate::PeerId, message: QueuedMessage) {
        let entry = self.outbox.entry(peer.short().as_str().to_string()).or_default();
        entry.push(message);
        // Capacity: 100 messages per peer, FIFO eviction.
        while entry.len() > crate::consts::OUTBOX_MAX_PER_PEER {
            entry.remove(0);
        }
    }

    /// All queued messages for a peer (oldest first).
    pub fn messages_for(&self, peer: &crate::PeerId) -> &[QueuedMessage] {
        self.outbox
            .get(peer.short().as_str())
            .map(|v| v.as_slice())
            .unwrap_or(&[])
    }

    /// Mark a send attempt; drops the message when attempts are exhausted or
    /// the 24 h TTL lapses. Returns the message to send, if any remain.
    pub fn attempt(&mut self, peer: &crate::PeerId, message_id: &str, now_ms: u64) -> Option<QueuedMessage> {
        let entry = self.outbox.get_mut(peer.short().as_str())?;
        let index = entry.iter().position(|m| m.message_id == message_id)?;
        entry[index].send_attempts += 1;
        if entry[index].send_attempts >= crate::consts::OUTBOX_MAX_SEND_ATTEMPTS
            || now_ms.saturating_sub(entry[index].timestamp_ms) > crate::consts::OUTBOX_TTL_S * 1000
        {
            entry.remove(index);
            return None;
        }
        Some(entry[index].clone())
    }

    /// Remove a delivered message.
    pub fn remove(&mut self, peer: &crate::PeerId, message_id: &str) {
        if let Some(entry) = self.outbox.get_mut(peer.short().as_str()) {
            entry.retain(|m| m.message_id != message_id);
        }
    }

    /// TTL sweep across all peers.
    pub fn remove_expired(&mut self, now_ms: u64) {
        let ttl = crate::consts::OUTBOX_TTL_S * 1000;
        for entry in self.outbox.values_mut() {
            entry.retain(|m| now_ms.saturating_sub(m.timestamp_ms) <= ttl);
        }
    }

    pub fn total(&self) -> usize {
        self.outbox.values().map(|v| v.len()).sum()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::CypherIdentity;

    fn seal_open_round_trip(prekey: bool) {
        let sender = CypherIdentity::generate();
        let recipient = CypherIdentity::generate();
        let recipient_pub = recipient.noise_static_public();
        let payload = courier_plaintext("m-1", "hidden note").unwrap();

        let (ciphertext, prekey_id) = if prekey {
            let mut prekey_seed = [0u8; 32];
            prekey_seed.copy_from_slice(&crate::util::sha256(&recipient_pub));
            let prekey_secret = StaticSecret::from(prekey_seed);
            let prekey_pub = *x25519_dalek::PublicKey::from(&prekey_secret).as_bytes();
            (
                seal_prekey_payload(&payload, sender.noise_static_secret(), 7, &prekey_pub).unwrap(),
                Some((7, prekey_secret)),
            )
        } else {
            (
                seal_courier_payload(&payload, sender.noise_static_secret(), &recipient_pub).unwrap(),
                None,
            )
        };

        let opened = match prekey_id {
            Some((id, secret)) => open_prekey_payload(&ciphertext, id, &secret).unwrap(),
            None => open_courier_payload(&ciphertext, recipient.noise_static_secret()).unwrap(),
        };
        assert_eq!(opened.0, payload);
        assert_eq!(opened.1, sender.noise_static_public());
    }

    #[test]
    fn v1_static_seal_round_trip() {
        seal_open_round_trip(false);
    }

    #[test]
    fn v2_prekey_seal_round_trip() {
        seal_open_round_trip(true);
    }

    #[test]
    fn wrong_recipient_cannot_open() {
        let sender = CypherIdentity::generate();
        let recipient = CypherIdentity::generate();
        let other = CypherIdentity::generate();
        let payload = courier_plaintext("m", "x").unwrap();
        let ciphertext =
            seal_courier_payload(&payload, sender.noise_static_secret(), &recipient.noise_static_public()).unwrap();
        assert!(open_courier_payload(&ciphertext, other.noise_static_secret()).is_err());
    }

    #[test]
    fn envelopes_not_linkable_across_sends() {
        // Fresh ephemeral per seal → different ciphertext AND different first
        // 32 bytes (the ephemeral key).
        let sender = CypherIdentity::generate();
        let recipient = CypherIdentity::generate();
        let payload = courier_plaintext("m", "same content").unwrap();
        let c1 = seal_courier_payload(&payload, sender.noise_static_secret(), &recipient.noise_static_public()).unwrap();
        let c2 = seal_courier_payload(&payload, sender.noise_static_secret(), &recipient.noise_static_public()).unwrap();
        assert_ne!(c1, c2);
        assert_ne!(&c1[..32], &c2[..32]);
    }

    #[test]
    fn make_envelope_uses_rotating_tag() {
        let sender = CypherIdentity::generate();
        let recipient = CypherIdentity::generate();
        let env = make_envelope(
            &recipient.noise_static_public(),
            sender.noise_static_secret(),
            "m-1",
            "hi",
            1_750_000_000_000,
            1_750_000_000,
            None,
        )
        .unwrap();
        // Tag matches the recipient's day tag.
        let day = CourierEnvelope::epoch_day(1_750_000_000);
        assert_eq!(
            env.recipient_tag,
            CourierEnvelope::recipient_tag(&recipient.noise_static_public(), day).to_vec()
        );
        // Recipient's candidate tags match (day ±1 window).
        let tags = CourierEnvelope::candidate_tags(&recipient.noise_static_public(), 1_750_000_000);
        assert!(env.matches_any(&tags));
    }

    #[test]
    fn store_quotas_and_eviction() {
        let mut store = CourierStore::new();
        let depositor = vec![0x11u8; 32];
        let build = |i: u8| {
            CourierEnvelope::new(
                vec![i; 16],
                1_750_000_000_000 + 3_600_000,
                vec![i; 64],
                1,
                None,
            )
        };
        // Verified depositor: max 2.
        assert!(store.deposit(build(1), depositor.clone(), CourierTier::Verified, 1_750_000_000_000));
        assert!(store.deposit(build(2), depositor.clone(), CourierTier::Verified, 1_750_000_000_000));
        assert!(!store.deposit(build(3), depositor.clone(), CourierTier::Verified, 1_750_000_000_000));
        // Favorite depositor: max 5.
        let fav = vec![0x22u8; 32];
        for i in 10..15u8 {
            assert!(store.deposit(build(i), fav.clone(), CourierTier::Favorite, 1_750_000_000_000));
        }
        assert!(!store.deposit(build(99), fav.clone(), CourierTier::Favorite, 1_750_000_000_000));
        assert_eq!(store.carried_count(), 7);
    }

    #[test]
    fn handover_takes_matching_envelopes() {
        let mut store = CourierStore::new();
        let recipient = CypherIdentity::generate();
        let day = CourierEnvelope::epoch_day(1_750_000_000);
        let tag = CourierEnvelope::recipient_tag(&recipient.noise_static_public(), day);
        let env = CourierEnvelope::new(tag.to_vec(), 1_750_000_100_000, vec![7u8; 64], 1, None);
        store.deposit(env.clone(), vec![1; 32], CourierTier::Verified, 1_750_000_000_000);
        store.deposit(CourierEnvelope::new(vec![9; 16], 1_750_000_100_000, vec![8u8; 64], 1, None), vec![1; 32], CourierTier::Verified, 1_750_000_000_000);
        let tags = CourierEnvelope::candidate_tags(&recipient.noise_static_public(), 1_750_000_000);
        let taken = store.take_for_handover(&tags);
        assert_eq!(taken.len(), 1);
        assert_eq!(taken[0].recipient_tag, tag.to_vec());
        assert_eq!(store.carried_count(), 1);
    }

    #[test]
    fn spray_halves_budgets() {
        let mut store = CourierStore::new();
        let depositor = vec![0x11u8; 32];
        let courier = vec![0x33u8; 32];
        let env = CourierEnvelope::new(vec![1; 16], 1_750_000_100_000, vec![7u8; 64], 4, None);
        store.deposit(env, depositor, CourierTier::Favorite, 1_750_000_000_000);
        let sprayed = store.transfer_spray_copies(&courier);
        assert_eq!(sprayed.len(), 1);
        assert_eq!(sprayed[0].copies, 2);
        // The retained copy has budget 2, already sprayed to this courier.
        let again = store.transfer_spray_copies(&courier);
        assert!(again.is_empty());
        // A different courier can still get the remaining budget.
        let other = store.transfer_spray_copies(&[0x44u8; 32]);
        assert_eq!(other.len(), 1);
        assert_eq!(other[0].copies, 1);
    }

    #[test]
    fn duplicate_deposit_does_not_replenish_budget() {
        let mut store = CourierStore::new();
        let depositor = vec![0x11u8; 32];
        let courier = vec![0x33u8; 32];
        let env = CourierEnvelope::new(vec![1; 16], 1_750_000_100_000, vec![7u8; 64], 4, None);
        assert!(store.deposit(env.clone(), depositor.clone(), CourierTier::Favorite, 1_750_000_000_000));
        let _ = store.transfer_spray_copies(&courier); // budget 4 -> 2
        // Replayed deposit with the full budget: the store must not
        // replenish the spent copies.
        assert!(store.deposit(env, depositor, CourierTier::Favorite, 1_750_000_000_001));
        let stored = &store.envelopes[0];
        assert_eq!(stored.copies, 2);
    }

    #[test]
    fn outbox_enqueue_attempt_expire() {
        let mut outbox = MessageOutboxStore::new();
        let peer = crate::PeerId::new("0011223344556677");
        outbox.enqueue(
            &peer,
            QueuedMessage {
                content: "retry me".into(),
                nickname: "alice".into(),
                message_id: "m-1".into(),
                timestamp_ms: 1000,
                send_attempts: 0,
                deposited_courier_keys: vec![],
            },
        );
        assert_eq!(outbox.messages_for(&peer).len(), 1);
        let m = outbox.attempt(&peer, "m-1", 2000).unwrap();
        assert_eq!(m.send_attempts, 1);
        outbox.remove(&peer, "m-1");
        assert_eq!(outbox.messages_for(&peer).len(), 0);
        // TTL sweep.
        outbox.enqueue(
            &peer,
            QueuedMessage {
                content: "old".into(),
                nickname: "a".into(),
                message_id: "m-2".into(),
                timestamp_ms: 1000,
                send_attempts: 0,
                deposited_courier_keys: vec![],
            },
        );
        outbox.remove_expired(1000 + crate::consts::OUTBOX_TTL_S * 1000 + 1);
        assert_eq!(outbox.total(), 0);
    }
}
