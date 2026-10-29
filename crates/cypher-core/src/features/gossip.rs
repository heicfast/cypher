//! Gossip sync: broadcast-packet reconciliation after churn.
//!
//! Syncable stores (public messages, group messages, fragments, file
//! transfers, prekey bundles, board posts) are diffed against peers via
//! Golomb-coded-set filters. Private/courier traffic never syncs.

use crate::protocol::sync::{GcsFilter, PacketIdUtil, RequestSyncPacket, SyncTypeFlags};
use crate::types::MessageType;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// A stored broadcast packet with its computed packet ID.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct StoredPacket {
    pub packet: Vec<u8>, // raw encoded frame (originator bytes preserved)
    pub packet_id: [u8; 16],
    pub timestamp_ms: u64,
    pub packet_type: u8,
}

/// Bounded store: newest-first retention, byte budget, oldest eviction.
pub struct PacketStore {
    packets: Vec<StoredPacket>, // newest first
    capacity: usize,
    byte_budget: usize,
    bytes: usize,
}

impl PacketStore {
    pub fn new(capacity: usize, byte_budget: usize) -> Self {
        PacketStore {
            packets: Vec::new(),
            capacity,
            byte_budget,
            bytes: 0,
        }
    }

    /// Insert newest-first; reject duplicates and out-of-window timestamps.
    pub fn insert(
        &mut self,
        packet: &crate::packet::BitchatPacket,
        max_age_ms: u64,
        now_ms: u64,
    ) -> Option<[u8; 16]> {
        // Freshness: never store future-dated or stale packets.
        if packet.timestamp > now_ms + crate::consts::BLE_MAX_TIMESTAMP_SKEW_MS {
            return None;
        }
        if now_ms.saturating_sub(packet.timestamp) > max_age_ms {
            return None;
        }
        let id = PacketIdUtil::packet_id(
            packet.packet_type,
            &packet.sender_id,
            packet.timestamp,
            &packet.payload,
        );
        if self.packets.iter().any(|p| p.packet_id == id) {
            return None;
        }
        let encoded = packet.to_binary_data(false)?;
        self.bytes += encoded.len();
        let stored = StoredPacket {
            packet: encoded,
            packet_id: id,
            timestamp_ms: packet.timestamp,
            packet_type: packet.packet_type,
        };
        // Keep the store strictly newest-first (bitchat's trimming semantics
        // drop the OLDEST tail when the byte budget binds).
        let position = self
            .packets
            .partition_point(|p| p.timestamp_ms >= stored.timestamp_ms);
        self.packets.insert(position, stored);
        // Evict oldest (tail) past capacity or byte budget.
        while self.packets.len() > self.capacity
            || (self.bytes > self.byte_budget && self.packets.len() > 1)
        {
            if let Some(removed) = self.packets.pop() {
                self.bytes = self.bytes.saturating_sub(removed.packet.len());
            }
        }
        Some(id)
    }

    pub fn ids_newest_first(&self) -> Vec<[u8; 16]> {
        self.packets.iter().map(|p| p.packet_id).collect()
    }

    pub fn since_cursor(&self) -> u64 {
        self.packets.last().map(|p| p.timestamp_ms).unwrap_or(0)
    }

    /// Packets not contained in the requester's filter (and newer than the
    /// cursor, except announce/prekey bundles which ignore the cursor).
    pub fn missing_from(&self, request: &RequestSyncPacket) -> Vec<Vec<u8>> {
        let values = GcsFilter::decode_ids(&request.data, request.p, request.m);
        let mut out = Vec::new();
        for stored in &self.packets {
            if stored.timestamp_ms < request.since_timestamp
                && stored.packet_type != MessageType::Announce.as_u8()
                && stored.packet_type != MessageType::PrekeyBundle.as_u8()
            {
                continue;
            }
            let bucket = GcsFilter::bucket(&stored.packet_id, request.m);
            if !values.binary_search(&bucket).is_ok() {
                out.push(stored.packet.clone());
            }
        }
        out
    }

    pub fn len(&self) -> usize {
        self.packets.len()
    }

    pub fn is_empty(&self) -> bool {
        self.packets.is_empty()
    }
}

/// Sync scheduling state per store class.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum StoreClass {
    Messages,
    Fragments,
    Files,
    Prekeys,
    Board,
    GroupMessages,
}

impl StoreClass {
    pub fn interval_s(&self) -> u64 {
        match self {
            StoreClass::Messages => crate::consts::SYNC_INTERVAL_MESSAGES_S,
            StoreClass::Fragments => crate::consts::SYNC_INTERVAL_FRAGMENTS_S,
            StoreClass::Files => crate::consts::SYNC_INTERVAL_FILES_S,
            StoreClass::Prekeys => crate::consts::SYNC_INTERVAL_PREKEYS_S,
            StoreClass::Board => crate::consts::SYNC_INTERVAL_BOARD_S,
            StoreClass::GroupMessages => crate::consts::SYNC_INTERVAL_MESSAGES_S,
        }
    }

    pub fn max_age_ms(&self) -> u64 {
        match self {
            StoreClass::Messages | StoreClass::GroupMessages => {
                crate::consts::SYNC_PUBLIC_MESSAGE_MAX_AGE_S * 1000
            }
            StoreClass::Fragments | StoreClass::Files | StoreClass::Board => {
                crate::consts::SYNC_MAX_MESSAGE_AGE_S * 1000
            }
            StoreClass::Prekeys => crate::consts::PREKEY_FRESHNESS_S * 1000,
        }
    }

    pub fn flag(&self) -> u64 {
        match self {
            StoreClass::Messages => SyncTypeFlags::MESSAGE,
            StoreClass::Fragments => SyncTypeFlags::FRAGMENT,
            StoreClass::Files => SyncTypeFlags::FILE_TRANSFER,
            StoreClass::Prekeys => SyncTypeFlags::PREKEY_BUNDLE,
            StoreClass::Board => SyncTypeFlags::BOARD_POST,
            StoreClass::GroupMessages => SyncTypeFlags::GROUP_MESSAGE,
        }
    }
}

/// Tracks outgoing sync requests: 30 s response window per peer.
#[derive(Default)]
pub struct RequestSyncManager {
    pending: HashMap<String, u64>, // peer -> request time ms
}

impl RequestSyncManager {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn record_request(&mut self, peer: &crate::PeerId, now_ms: u64) {
        self.pending
            .insert(peer.short().as_str().to_string(), now_ms);
    }

    /// A response (RSR-flagged packet) is valid only from a peer with an
    /// open request window.
    pub fn is_valid_response(&mut self, peer: &crate::PeerId, now_ms: u64) -> bool {
        let key = peer.short().as_str().to_string();
        match self.pending.get(&key) {
            Some(t) => {
                if now_ms.saturating_sub(*t) <= crate::consts::SYNC_RESPONSE_WINDOW_S * 1000 {
                    true
                } else {
                    self.pending.remove(&key);
                    false
                }
            }
            None => false,
        }
    }

    pub fn cleanup(&mut self, now_ms: u64) {
        let window = crate::consts::SYNC_RESPONSE_WINDOW_S * 1000;
        self.pending
            .retain(|_, t| now_ms.saturating_sub(*t) <= window);
    }
}

/// Response rate limiting: 8 responses per 30 s per peer.
#[derive(Default)]
pub struct SyncResponseRateLimiter {
    responses: HashMap<String, Vec<u64>>,
}

impl SyncResponseRateLimiter {
    pub fn allow(&mut self, peer: &crate::PeerId, now_ms: u64) -> bool {
        let key = peer.short().as_str().to_string();
        let window = crate::consts::SYNC_RESPONSE_WINDOW_SLICE_S * 1000;
        let entry = self.responses.entry(key).or_default();
        entry.retain(|t| now_ms.saturating_sub(*t) <= window);
        if entry.len() >= crate::consts::SYNC_RESPONSE_MAX_PER_WINDOW {
            return false;
        }
        entry.push(now_ms);
        true
    }
}

/// The gossip manager: stores + scheduling + request/response assembly.
pub struct GossipSyncManager {
    pub messages: PacketStore,
    pub group_messages: PacketStore,
    pub fragments: PacketStore,
    pub files: PacketStore,
    pub prekeys: PacketStore,
    pub board: PacketStore,
    pub request_manager: RequestSyncManager,
    pub response_limiter: SyncResponseRateLimiter,
    last_sync_ms: HashMap<u8, u64>, // store class -> last round
    now_ms: Box<dyn Fn() -> u64 + Send + Sync>,
}

impl GossipSyncManager {
    pub fn new() -> Self {
        Self::with_clock(Box::new(crate::util::now_ms))
    }

    pub fn with_clock(now_ms: Box<dyn Fn() -> u64 + Send + Sync>) -> Self {
        GossipSyncManager {
            messages: PacketStore::new(
                crate::consts::SYNC_STORE_MESSAGES,
                crate::consts::SYNC_STORE_MESSAGES_BYTES,
            ),
            group_messages: PacketStore::new(
                crate::consts::SYNC_STORE_GROUP_MESSAGES,
                crate::consts::SYNC_STORE_GROUP_MESSAGES_BYTES,
            ),
            fragments: PacketStore::new(
                crate::consts::SYNC_STORE_FRAGMENTS,
                crate::consts::SYNC_STORE_FRAGMENTS_BYTES,
            ),
            files: PacketStore::new(
                crate::consts::SYNC_STORE_FILES,
                crate::consts::SYNC_STORE_FILES_BYTES,
            ),
            prekeys: PacketStore::new(crate::consts::SYNC_STORE_PREKEYS, 1024 * 1024),
            board: PacketStore::new(200, 1024 * 1024),
            request_manager: RequestSyncManager::new(),
            response_limiter: SyncResponseRateLimiter::default(),
            last_sync_ms: HashMap::new(),
            now_ms,
        }
    }

    fn now(&self) -> u64 {
        (self.now_ms)()
    }

    fn store(&self, class: StoreClass) -> &PacketStore {
        match class {
            StoreClass::Messages => &self.messages,
            StoreClass::Fragments => &self.fragments,
            StoreClass::Files => &self.files,
            StoreClass::Prekeys => &self.prekeys,
            StoreClass::Board => &self.board,
            StoreClass::GroupMessages => &self.group_messages,
        }
    }

    fn store_mut(&mut self, class: StoreClass) -> &mut PacketStore {
        match class {
            StoreClass::Messages => &mut self.messages,
            StoreClass::Fragments => &mut self.fragments,
            StoreClass::Files => &mut self.files,
            StoreClass::Prekeys => &mut self.prekeys,
            StoreClass::Board => &mut self.board,
            StoreClass::GroupMessages => &mut self.group_messages,
        }
    }

    /// Record a broadcast packet in the appropriate store (gossip candidates).
    pub fn observe(&mut self, packet: &crate::packet::BitchatPacket) {
        let class = match MessageType::from_u8(packet.packet_type) {
            Some(MessageType::Message) => StoreClass::Messages,
            Some(MessageType::GroupMessage) => StoreClass::GroupMessages,
            Some(MessageType::Fragment) => StoreClass::Fragments,
            Some(MessageType::FileTransfer) => StoreClass::Files,
            Some(MessageType::PrekeyBundle) => StoreClass::Prekeys,
            Some(MessageType::BoardPost) => StoreClass::Board,
            _ => return,
        };
        let now = self.now();
        self.store_mut(class)
            .insert(packet, class.max_age_ms(), now);
    }

    /// Is a sync round due for any store class?
    pub fn sync_due(&self) -> Option<Vec<StoreClass>> {
        let now = self.now();
        let mut due = Vec::new();
        for class in [
            StoreClass::Messages,
            StoreClass::GroupMessages,
            StoreClass::Fragments,
            StoreClass::Files,
            StoreClass::Prekeys,
            StoreClass::Board,
        ] {
            let last = self.last_sync_ms.get(&(class as u8)).copied().unwrap_or(0);
            if now.saturating_sub(last) >= class.interval_s() * 1000 {
                due.push(class);
            }
        }
        if due.is_empty() {
            None
        } else {
            Some(due)
        }
    }

    /// Build a REQUEST_SYNC packet covering the given store classes.
    pub fn build_request(&mut self, classes: &[StoreClass]) -> Option<RequestSyncPacket> {
        let now = self.now();
        let mut flags = SyncTypeFlags::default();
        let mut ids: Vec<[u8; 16]> = Vec::new();
        let mut oldest_cursor = u64::MAX;
        for &class in classes {
            flags.insert(class.flag());
            let store = self.store(class);
            ids.extend(store.ids_newest_first());
            oldest_cursor = oldest_cursor.min(store.since_cursor());
        }
        if ids.is_empty() {
            return None;
        }
        let p = GcsFilter::derive_p(crate::consts::SYNC_TARGET_FPR);
        let (data, m, included) =
            GcsFilter::build_filter(&ids, p, crate::consts::SYNC_GCS_MAX_BYTES);
        // The exact cursor: the newest-prefix coverage boundary.
        // The exact cursor: the oldest timestamp still covered by the
        // trimmed filter (each store contributes its newest-prefix share).
        let mut cursor_count = included;
        let mut since = 0u64;
        for &class in classes {
            let store = self.store(class);
            let ids_class = store.ids_newest_first();
            let share = cursor_count.min(ids_class.len());
            if share > 0 {
                since = since.max(store.since_cursor());
            }
            cursor_count = cursor_count.saturating_sub(share);
        }
        for &class in classes {
            self.last_sync_ms.insert(class as u8, now);
        }
        Some(RequestSyncPacket {
            p,
            m,
            data,
            types: flags,
            since_timestamp: since,
            fragment_id_filter: None,
        })
    }

    /// Serve a request: packets the requester is missing, ready for directed
    /// RSR-flagged re-sends. Rate limited per peer.
    pub fn serve_request(
        &mut self,
        peer: &crate::PeerId,
        request: &RequestSyncPacket,
    ) -> Option<Vec<Vec<u8>>> {
        let now = self.now();
        if !self.response_limiter.allow(peer, now) {
            return None;
        }
        let mut out = Vec::new();
        for class in [
            StoreClass::Messages,
            StoreClass::GroupMessages,
            StoreClass::Fragments,
            StoreClass::Files,
            StoreClass::Prekeys,
            StoreClass::Board,
        ] {
            if !request.types.contains(class.flag()) {
                continue;
            }
            out.extend(self.store(class).missing_from(request));
        }
        Some(out)
    }
}

impl Default for GossipSyncManager {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::packet::BitchatPacket;

    fn broadcast(t: MessageType, timestamp_ms: u64, payload: Vec<u8>) -> BitchatPacket {
        let mut p = BitchatPacket::new(
            t.as_u8(),
            [1, 2, 3, 4, 5, 6, 7, 8],
            None,
            timestamp_ms,
            payload,
            7,
        );
        p.signature = Some([9; 64]);
        p
    }

    #[test]
    fn store_dedups_and_respects_window() {
        let mut store = PacketStore::new(10, 1024 * 1024);
        let p = broadcast(MessageType::Message, 1000, b"hello".to_vec());
        assert!(store.insert(&p, 900_000, 2000).is_some());
        assert!(
            store.insert(&p, 900_000, 2000).is_none(),
            "duplicate rejected"
        );
        // Stale packet rejected (age strictly beyond the window).
        let old = broadcast(MessageType::Message, 0, b"old".to_vec());
        assert!(store.insert(&old, 900_000, 900_001).is_none());
        // Future-dated packet rejected.
        let future = broadcast(MessageType::Message, 900_000, b"f".to_vec());
        assert!(store.insert(&future, 900_000, 0).is_none());
    }

    #[test]
    fn request_response_round_trip() {
        // Alice holds every 5th message (10 of 50); Bob holds all 50.
        let clock = std::sync::Arc::new(std::sync::atomic::AtomicU64::new(10_000));
        let c2 = clock.clone();
        let mut a = GossipSyncManager::with_clock(Box::new(move || {
            c2.load(std::sync::atomic::Ordering::SeqCst)
        }));

        for i in 0..50u64 {
            if i % 5 == 0 {
                let p = broadcast(
                    MessageType::Message,
                    10_000 - i,
                    format!("msg-{i}").into_bytes(),
                );
                a.observe(&p);
            }
        }
        assert_eq!(a.messages.len(), 10);

        // She builds a request covering what she has.
        let request = a.build_request(&[StoreClass::Messages]).unwrap();
        assert!(request.types.contains(SyncTypeFlags::MESSAGE));

        // Bob serves the diff: the ~40 messages Alice lacks.
        let mut b = GossipSyncManager::with_clock(Box::new(|| 10_000));
        for i in 0..50u64 {
            let p = broadcast(
                MessageType::Message,
                10_000 - i,
                format!("msg-{i}").into_bytes(),
            );
            b.observe(&p);
        }
        let alice_peer = crate::PeerId::new("00a1");
        let missing = b.serve_request(&alice_peer, &request).unwrap();
        assert!(
            missing.len() >= 35 && missing.len() <= 40,
            "expected most of the 40 missing (cursor trims a few), got {}",
            missing.len()
        );
        // Responses are rate limited to 8 per 30 s.
        for _ in 0..7 {
            assert!(b.serve_request(&alice_peer, &request).is_some());
        }
        assert!(
            b.serve_request(&alice_peer, &request).is_none(),
            "rate limited"
        );
    }

    #[test]
    fn request_window_validation() {
        let mut rm = RequestSyncManager::new();
        let peer = crate::PeerId::new("00b2");
        rm.record_request(&peer, 1000);
        assert!(rm.is_valid_response(&peer, 1000 + 29_000));
        assert!(!rm.is_valid_response(&peer, 1000 + 31_000));
        // Unsolicited responses rejected.
        assert!(!rm.is_valid_response(&crate::PeerId::new("00cc"), 1000));
    }
}
