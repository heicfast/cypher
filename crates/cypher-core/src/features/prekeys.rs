//! Local prekey management: generation, consumption, bundle publication.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use x25519_dalek::StaticSecret;

/// A one-time prekey: private half held locally, public half published in
/// signed bundles.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct LocalPrekey {
    pub id: u32,
    /// Raw 32-byte private key.
    pub private_key: Vec<u8>,
    pub created_at_ms: u64,
    pub consumed_at_ms: Option<u64>,
}

/// Persisted store state.
#[derive(Default, Debug, Serialize, Deserialize)]
pub struct LocalPrekeyState {
    pub records: Vec<LocalPrekey>,
    pub next_id: u32,
    pub generated_at_ms: u64,
}

/// Policy: batch 8, replenish below 3 unconsumed, 48 h consumed grace,
/// 30 d unconsumed retention.
pub struct LocalPrekeyStore {
    state: LocalPrekeyState,
    now_ms: Box<dyn Fn() -> u64 + Send + Sync>,
}

impl LocalPrekeyStore {
    pub fn new() -> Self {
        Self::with_clock(Box::new(crate::util::now_ms))
    }

    pub fn with_clock(now_ms: Box<dyn Fn() -> u64 + Send + Sync>) -> Self {
        let mut store = LocalPrekeyStore {
            state: LocalPrekeyState::default(),
            now_ms,
        };
        store.replenish();
        store
    }

    fn now(&self) -> u64 {
        (self.now_ms)()
    }

    /// Top up to the batch size when unconsumed prekeys fall below the
    /// replenish threshold. `generatedAt` must be strictly monotonic.
    fn replenish(&mut self) {
        let now = self.now();
        let unconsumed = self
            .state
            .records
            .iter()
            .filter(|r| r.consumed_at_ms.is_none())
            .count();
        if unconsumed >= crate::consts::PREKEY_REPLENISH_THRESHOLD {
            return;
        }
        let needed = crate::consts::PREKEY_BATCH_SIZE - unconsumed;
        for _ in 0..needed {
            let secret = StaticSecret::random_from_rng(rand_core::OsRng);
            self.state.records.push(LocalPrekey {
                id: self.state.next_id,
                private_key: secret.to_bytes().to_vec(),
                created_at_ms: now,
                consumed_at_ms: None,
            });
            self.state.next_id += 1;
        }
        // Monotonic generatedAt (ms).
        self.state.generated_at_ms = now.max(self.state.generated_at_ms + 1);
    }

    pub fn generated_at_ms(&self) -> u64 {
        self.state.generated_at_ms
    }

    /// Build the signed public bundle for the owner's static key.
    pub fn build_bundle(
        &self,
        owner: &crate::identity::CypherIdentity,
    ) -> Option<crate::protocol::prekey::PrekeyBundle> {
        let mut prekeys = Vec::new();
        for record in self
            .state
            .records
            .iter()
            .filter(|r| r.consumed_at_ms.is_none())
        {
            let bytes: [u8; 32] = record.private_key.clone().try_into().ok()?;
            let secret = StaticSecret::from(bytes);
            prekeys.push(crate::protocol::prekey::Prekey {
                id: record.id,
                public_key: x25519_dalek::PublicKey::from(&secret).as_bytes().to_vec(),
            });
            if prekeys.len() >= crate::consts::PREKEY_BATCH_SIZE {
                break;
            }
        }
        if prekeys.is_empty() {
            return None;
        }
        let mut bundle = crate::protocol::prekey::PrekeyBundle {
            noise_static_public_key: owner.noise_static_public().to_vec(),
            prekeys,
            generated_at: self.state.generated_at_ms,
            signature: vec![0; 64],
        };
        bundle.signature = owner.sign(&bundle.signable_bytes()).to_vec();
        Some(bundle)
    }

    /// Consume a prekey by ID (opening a v2 seal). Consumed keys stay for the
    /// 48 h redelivery grace window, then die.
    pub fn consume(&mut self, id: u32) -> Option<StaticSecret> {
        let now = self.now();
        let record = self.state.records.iter_mut().find(|r| r.id == id)?;
        if let Some(consumed_at) = record.consumed_at_ms {
            // Within the grace window: still usable for redelivery.
            if now.saturating_sub(consumed_at) > crate::consts::PREKEY_CONSUMED_GRACE_S * 1000 {
                return None;
            }
        } else {
            record.consumed_at_ms = Some(now);
        }
        let bytes: [u8; 32] = record.private_key.clone().try_into().ok()?;
        let secret = StaticSecret::from(bytes);
        self.replenish();
        Some(secret)
    }

    /// Whether the store can open prekey `id` (for opening seals).
    pub fn can_open(&self, id: u32) -> bool {
        self.state.records.iter().any(|r| r.id == id)
    }

    /// Sweep: drop consumed keys past the grace window and unconsumed keys
    /// past the retention window.
    pub fn sweep(&mut self) {
        let now = self.now();
        self.state.records.retain(|r| match r.consumed_at_ms {
            Some(consumed) => {
                now.saturating_sub(consumed) <= crate::consts::PREKEY_CONSUMED_GRACE_S * 1000
            }
            None => {
                now.saturating_sub(r.created_at_ms)
                    <= crate::consts::PREKEY_UNCONSUMED_RETENTION_S * 1000
            }
        });
        self.replenish();
    }

    pub fn unconsumed_count(&self) -> usize {
        self.state
            .records
            .iter()
            .filter(|r| r.consumed_at_ms.is_none())
            .count()
    }

    pub fn to_state(&self) -> &LocalPrekeyState {
        &self.state
    }

    pub fn from_state(state: LocalPrekeyState, now_ms: Box<dyn Fn() -> u64 + Send + Sync>) -> Self {
        let mut store = LocalPrekeyStore { state, now_ms };
        store.sweep();
        store
    }
}

impl Default for LocalPrekeyStore {
    fn default() -> Self {
        Self::new()
    }
}

// ---------------------------------------------------------------------------
// PrekeyBundleStore — other peers' signed bundles (sender side)
// ---------------------------------------------------------------------------

/// A peer's bundle plus usage tracking.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct StoredBundle {
    pub bundle: crate::protocol::prekey::PrekeyBundle,
    pub updated_at_ms: u64,
    /// messageID → assigned prekeyID (re-deposits reuse the same prekey).
    pub assignments: HashMap<String, u32>,
    pub used_ids: Vec<u32>,
}

/// Bundles cached for sealing; keyed by the bundle's authenticated noise
/// static key (not the spoofable packet sender).
#[derive(Default)]
pub struct PrekeyBundleStore {
    bundles: HashMap<String, StoredBundle>,
    order: Vec<String>,
}

impl PrekeyBundleStore {
    pub fn new() -> Self {
        Self::default()
    }

    /// Store a verified bundle. Newer `generatedAt` wins; the key is the
    /// bundle's noise static key (hex).
    pub fn store_verified(
        &mut self,
        bundle: crate::protocol::prekey::PrekeyBundle,
        now_ms: u64,
    ) -> bool {
        // Freshness: bundles older than 24 h are not accepted.
        if now_ms.saturating_sub(bundle.generated_at) > crate::consts::PREKEY_FRESHNESS_S * 1000 {
            return false;
        }
        let key = crate::util::hex_encode(&bundle.noise_static_public_key);
        match self.bundles.get_mut(&key) {
            Some(existing) => {
                if bundle.generated_at > existing.bundle.generated_at {
                    // Preserve assignments/used state on refresh.
                    existing.bundle = bundle;
                    existing.updated_at_ms = now_ms;
                }
                true
            }
            None => {
                self.bundles.insert(
                    key.clone(),
                    StoredBundle {
                        bundle,
                        updated_at_ms: now_ms,
                        assignments: HashMap::new(),
                        used_ids: Vec::new(),
                    },
                );
                self.order.push(key);
                // LRU by updatedAt at the 200-peer cap.
                if self.bundles.len() > crate::consts::PREKEY_STORE_MAX_PEERS {
                    if let Some(oldest) = self
                        .order
                        .iter()
                        .min_by_key(|k| self.bundles.get(*k).map(|b| b.updated_at_ms).unwrap_or(0))
                        .cloned()
                    {
                        self.bundles.remove(&oldest);
                        self.order.retain(|k| *k != oldest);
                    }
                }
                true
            }
        }
    }

    /// The freshest bundle for a noise static key.
    pub fn bundle_for(
        &self,
        noise_static_key: &[u8],
    ) -> Option<&crate::protocol::prekey::PrekeyBundle> {
        self.bundles
            .get(&crate::util::hex_encode(noise_static_key))
            .map(|s| &s.bundle)
    }

    /// Assign a prekey for a message: the lowest unused ID, stable per
    /// message (re-deposits reuse it). Only fresh bundles (≤ 7 days) seal.
    pub fn assign_prekey(
        &mut self,
        noise_static_key: &[u8],
        message_id: &str,
        now_ms: u64,
    ) -> Option<(u32, [u8; 32])> {
        let key = crate::util::hex_encode(noise_static_key);
        let stored = self.bundles.get_mut(&key)?;
        if now_ms.saturating_sub(stored.bundle.generated_at)
            > crate::consts::PREKEY_MAX_BUNDLE_AGE_FOR_SEALING_S * 1000
        {
            return None;
        }
        if let Some(&assigned) = stored.assignments.get(message_id) {
            let pk = stored.bundle.prekeys.iter().find(|p| p.id == assigned)?;
            let pub_bytes: [u8; 32] = pk.public_key.clone().try_into().ok()?;
            return Some((assigned, pub_bytes));
        }
        let pk = stored
            .bundle
            .prekeys
            .iter()
            .find(|p| !stored.used_ids.contains(&p.id))?;
        let pub_bytes: [u8; 32] = pk.public_key.clone().try_into().ok()?;
        stored.assignments.insert(message_id.to_string(), pk.id);
        stored.used_ids.push(pk.id);
        Some((pk.id, pub_bytes))
    }

    pub fn len(&self) -> usize {
        self.bundles.len()
    }

    pub fn is_empty(&self) -> bool {
        self.bundles.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::CypherIdentity;

    struct FixedClock(u64);
    impl FixedClock {
        fn at(ms: u64) -> Box<dyn Fn() -> u64 + Send + Sync> {
            let cell = std::sync::Arc::new(std::sync::atomic::AtomicU64::new(ms));
            let c = cell.clone();
            Box::new(move || c.load(std::sync::atomic::Ordering::SeqCst))
        }
    }

    #[test]
    fn mints_full_batch_on_first_use() {
        let owner = CypherIdentity::generate();
        let store = LocalPrekeyStore::with_clock(FixedClock::at(1000));
        let bundle = store.build_bundle(&owner).unwrap();
        assert_eq!(bundle.prekeys.len(), crate::consts::PREKEY_BATCH_SIZE);
        assert!(bundle.verify_signature(&owner.signing_public()));
        // IDs are 0..7.
        assert_eq!(bundle.prekeys[0].id, 0);
        assert_eq!(bundle.prekeys[7].id, 7);
    }

    #[test]
    fn consumption_replenishes_below_threshold() {
        let owner = CypherIdentity::generate();
        let mut store = LocalPrekeyStore::with_clock(FixedClock::at(1000));
        assert_eq!(store.unconsumed_count(), crate::consts::PREKEY_BATCH_SIZE);
        // Consuming down to the threshold (3) does not yet replenish.
        for id in
            0..(crate::consts::PREKEY_BATCH_SIZE - crate::consts::PREKEY_REPLENISH_THRESHOLD) as u32
        {
            store.consume(id).unwrap();
        }
        assert_eq!(
            store.unconsumed_count(),
            crate::consts::PREKEY_REPLENISH_THRESHOLD
        );
        let bundle = store.build_bundle(&owner).unwrap();
        assert_eq!(
            bundle.prekeys.len(),
            crate::consts::PREKEY_REPLENISH_THRESHOLD
        );
        assert!(
            !bundle.prekeys.iter().any(|p| p.id == 0),
            "consumed key not republished"
        );
        // One more consumption crosses the threshold and tops the batch up.
        store
            .consume(
                crate::consts::PREKEY_BATCH_SIZE as u32
                    - crate::consts::PREKEY_REPLENISH_THRESHOLD as u32,
            )
            .unwrap();
        assert_eq!(store.unconsumed_count(), crate::consts::PREKEY_BATCH_SIZE);
        let bundle = store.build_bundle(&owner).unwrap();
        assert_eq!(bundle.prekeys.len(), crate::consts::PREKEY_BATCH_SIZE);
        assert!(bundle.verify_signature(&owner.signing_public()));
    }

    #[test]
    fn consumed_prekey_survives_grace_then_dies() {
        let clock = std::sync::Arc::new(std::sync::atomic::AtomicU64::new(1000));
        let c2 = clock.clone();
        let mut store = LocalPrekeyStore::with_clock(Box::new(move || {
            c2.load(std::sync::atomic::Ordering::SeqCst)
        }));
        store.consume(0).unwrap();
        // Within the grace window, redelivery re-opens.
        clock.store(1000 + 1000, std::sync::atomic::Ordering::SeqCst);
        assert!(store.can_open(0));
        // Past the grace window, sweep removes it.
        clock.store(
            1000 + crate::consts::PREKEY_CONSUMED_GRACE_S * 1000 + 1,
            std::sync::atomic::Ordering::SeqCst,
        );
        store.sweep();
        assert!(!store.can_open(0));
    }

    #[test]
    fn bundle_store_newer_wins_and_assigns() {
        let owner = CypherIdentity::generate();
        let mut remote = LocalPrekeyStore::with_clock(FixedClock::at(1000));
        let bundle = remote.build_bundle(&owner).unwrap();

        let mut store = PrekeyBundleStore::new();
        assert!(store.store_verified(bundle.clone(), 2000));
        // Older bundle does not replace.
        let mut older = bundle.clone();
        older.generated_at = bundle.generated_at - 1;
        assert!(store.store_verified(older, 3000));
        assert_eq!(
            store
                .bundle_for(&owner.noise_static_public())
                .unwrap()
                .generated_at,
            bundle.generated_at
        );

        // Assignment: lowest unused, stable per message.
        let (id1, key1) = store
            .assign_prekey(&owner.noise_static_public(), "msg-a", 3000)
            .unwrap();
        assert_eq!(id1, 0);
        let (id2, _) = store
            .assign_prekey(&owner.noise_static_public(), "msg-b", 3000)
            .unwrap();
        assert_eq!(id2, 1);
        // Re-assign for the same message reuses its prekey.
        let (id3, key3) = store
            .assign_prekey(&owner.noise_static_public(), "msg-a", 3000)
            .unwrap();
        assert_eq!(id3, 0);
        assert_eq!(key1, key3);
    }

    #[test]
    fn stale_bundles_not_stored() {
        let owner = CypherIdentity::generate();
        let mut remote = LocalPrekeyStore::with_clock(FixedClock::at(1000));
        let mut bundle = remote.build_bundle(&owner).unwrap();
        bundle.generated_at = 1000;
        let mut store = PrekeyBundleStore::new();
        // 25 h old → rejected.
        assert!(!store.store_verified(bundle, 1000 + 25 * 3600 * 1000));
    }
}
