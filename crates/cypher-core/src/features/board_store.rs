//! Board (announcement wall) store — exact port of bitchat's
//! `BoardStore.swift` + `BoardPackets.swift` ingest rules.
//!
//! The wire format and signature verification live in
//! `protocol/board.rs`; this store owns admission, caps, pruning, and
//! persistence (JSON to the host via injectable save/load).

use crate::peer_id::PeerId;
use crate::protocol::board::{BoardPostPacket, BoardTombstonePacket, BoardWire};
use serde::{Deserialize, Serialize};

pub type PostId = Vec<u8>;
pub type AuthorKey = Vec<u8>;
use std::collections::{HashMap, HashSet};

pub const MAX_POSTS: usize = 200;
pub const MAX_POSTS_PER_AUTHOR: usize = 5;
pub const ORPHAN_TOMBSTONE_LIFETIME_MS: u64 = 7 * 24 * 60 * 60 * 1000;
pub const MAX_ORPHAN_TOMBSTONES: usize = 100;
pub const MAX_ORPHAN_TOMBSTONES_PER_AUTHOR: usize = 5;
pub const CLOCK_SKEW_MS: u64 = 60 * 60 * 1000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BoardIngestResult {
    /// Relay it.
    Accepted,
    /// Already known — don't relay.
    Duplicate,
    /// Invalid/expired/deleted — don't relay.
    Rejected,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct StoredPost {
    pub wire: BoardWire,
    /// Depositor (relay) peer — for analytics only.
    #[serde(default)]
    pub received_from: Option<PeerId>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct StoredTombstone {
    pub post_id: PostId,
    pub author_signing_key: AuthorKey,
    pub deleted_at_ms: u64,
    pub retain_until_ms: u64,
    pub is_orphan: bool,
    #[serde(default)]
    pub received_from: Option<PeerId>,
}

/// On-disk record (Swift `posts.json` entries).
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct BoardRecord {
    pub post: Option<StoredPost>,
    pub tombstone: Option<StoredTombstone>,
    /// Restored-from-disk retain override (Swift `retainUntil`).
    pub retain_until: Option<u64>,
}

pub struct BoardStore {
    posts: Vec<StoredPost>,
    tombstones: Vec<StoredTombstone>,
    /// Post-ID index for duplicate checks.
    seen_post_ids: HashSet<PostId>,
    clock: crate::noise::session_manager::Clock,
}

impl BoardStore {
    pub fn new(clock: crate::noise::session_manager::Clock) -> Self {
        BoardStore {
            posts: Vec::new(),
            tombstones: Vec::new(),
            seen_post_ids: HashSet::new(),
            clock,
        }
    }

    fn now_ms(&self) -> u64 {
        (self.clock)()
    }

    /// Swift `ingest(_:)` — signature must already be verified by the
    /// caller (the wire layer refuses unverified payloads).
    pub fn ingest(&mut self, wire: &BoardWire) -> BoardIngestResult {
        let now = self.now_ms();
        self.prune_expired(now);
        match wire {
            BoardWire::Post(post) => self.ingest_post(post, now),
            BoardWire::Tombstone(tomb) => self.ingest_tombstone(tomb, now, None),
        }
    }

    /// Launch restore path — the retain override from disk caps both
    /// doctored `deletedAt` and receive time.
    pub fn ingest_restore(
        &mut self,
        wire: &BoardWire,
        retain_until: Option<u64>,
    ) -> BoardIngestResult {
        let now = self.now_ms();
        self.prune_expired(now);
        match wire {
            BoardWire::Post(post) => self.ingest_post(post, now),
            BoardWire::Tombstone(tomb) => self.ingest_tombstone(tomb, now, retain_until),
        }
    }

    fn ingest_post(&mut self, post: &BoardPostPacket, now: u64) -> BoardIngestResult {
        // 1. Not yet expired.
        if post.expires_at <= now {
            return BoardIngestResult::Rejected;
        }
        // 2. Receive-time sanity: no forged future timestamps.
        if post.created_at > now + CLOCK_SKEW_MS
            || post.expires_at > now + ORPHAN_TOMBSTONE_LIFETIME_MS + CLOCK_SKEW_MS
        {
            return BoardIngestResult::Rejected;
        }
        // 3. A matching tombstone deletes the post on sight.
        if self
            .tombstones
            .iter()
            .any(|t| t.post_id == post.post_id && t.author_signing_key == post.author_signing_key)
        {
            return BoardIngestResult::Rejected;
        }
        // 4. Globally unique post IDs.
        if self.seen_post_ids.contains(&post.post_id) {
            return BoardIngestResult::Duplicate;
        }
        // 5. Append; per-author cap evicts oldest by createdAt, then the
        //    global cap evicts oldest-first. The new post stays relayable
        //    even if it is itself the eviction victim.
        self.posts.push(StoredPost {
            wire: BoardWire::Post(post.clone()),
            received_from: None,
        });
        self.seen_post_ids.insert(post.post_id.clone());
        self.enforce_author_cap(post.author_signing_key.clone());
        self.enforce_global_cap();
        BoardIngestResult::Accepted
    }

    fn ingest_tombstone(
        &mut self,
        tomb: &BoardTombstonePacket,
        now: u64,
        retain_override: Option<u64>,
    ) -> BoardIngestResult {
        // 1. Duplicate tombstone for the same post.
        if self.tombstones.iter().any(|t| t.post_id == tomb.post_id) {
            return BoardIngestResult::Duplicate;
        }
        // 2. maxRetain caps doctored future deletedAt and receive time.
        let max_retain = (tomb.deleted_at + ORPHAN_TOMBSTONE_LIFETIME_MS)
            .min(now + ORPHAN_TOMBSTONE_LIFETIME_MS + CLOCK_SKEW_MS);
        let (retain_until, is_orphan) =
            if let Some(pos) = self.posts.iter().position(
                |p| matches!(&p.wire, BoardWire::Post(post) if post.post_id == tomb.post_id),
            ) {
                // 3. Known post: the tombstone author must match the post's
                //    author key, else rejected.
                let author_matches = matches!(&self.posts[pos].wire,
                BoardWire::Post(post) if post.author_signing_key == tomb.author_signing_key);
                if !author_matches {
                    return BoardIngestResult::Rejected;
                }
                let retain_until = match &self.posts[pos].wire {
                    BoardWire::Post(post) => post.expires_at,
                    _ => unreachable!(),
                };
                self.posts.remove(pos);
                (retain_until, false)
            } else if let Some(override_ms) = retain_override {
                // 4. Restored from disk: min(override, maxRetain), not orphan.
                (override_ms.min(max_retain), false)
            } else {
                // 5. Orphan (post never seen).
                (max_retain, true)
            };
        if retain_until <= now {
            return BoardIngestResult::Rejected;
        }
        self.tombstones.push(StoredTombstone {
            post_id: tomb.post_id.clone(),
            author_signing_key: tomb.author_signing_key.clone(),
            deleted_at_ms: tomb.deleted_at,
            retain_until_ms: retain_until,
            is_orphan,
            received_from: None,
        });
        if is_orphan {
            self.enforce_orphan_caps();
        }
        BoardIngestResult::Accepted
    }

    fn enforce_author_cap(&mut self, author: AuthorKey) {
        loop {
            let count = self
                .posts
                .iter()
                .filter(|p| matches!(&p.wire, BoardWire::Post(post) if post.author_signing_key == author))
                .count();
            if count <= MAX_POSTS_PER_AUTHOR {
                return;
            }
            // Evict this author's oldest by createdAt.
            let mut oldest: Option<usize> = None;
            let mut oldest_created = u64::MAX;
            for (i, p) in self.posts.iter().enumerate() {
                if let BoardWire::Post(post) = &p.wire {
                    if post.author_signing_key == author && post.created_at < oldest_created {
                        oldest_created = post.created_at;
                        oldest = Some(i);
                    }
                }
            }
            if let Some(i) = oldest {
                if let BoardWire::Post(post) = &self.posts[i].wire {
                    self.seen_post_ids.remove(&post.post_id);
                }
                self.posts.remove(i);
            } else {
                return;
            }
        }
    }

    fn enforce_global_cap(&mut self) {
        while self.posts.len() > MAX_POSTS {
            if let Some(BoardWire::Post(post)) = self.posts.first().map(|p| p.wire.clone()) {
                self.seen_post_ids.remove(&post.post_id);
            }
            self.posts.remove(0);
        }
    }

    fn enforce_orphan_caps(&mut self) {
        // Per-author 5, then global 100, evicting oldest-received-first
        // (array order).
        let mut per_author: HashMap<AuthorKey, usize> = HashMap::new();
        for t in &self.tombstones {
            if t.is_orphan {
                *per_author.entry(t.author_signing_key.clone()).or_default() += 1;
            }
        }
        for (author, count) in per_author {
            let mut excess = count.saturating_sub(MAX_ORPHAN_TOMBSTONES_PER_AUTHOR);
            let mut i = 0;
            while i < self.tombstones.len() && excess > 0 {
                if self.tombstones[i].is_orphan && self.tombstones[i].author_signing_key == author {
                    self.tombstones.remove(i);
                    excess -= 1;
                } else {
                    i += 1;
                }
            }
        }
        while self.tombstones.iter().filter(|t| t.is_orphan).count() > MAX_ORPHAN_TOMBSTONES {
            if let Some(pos) = self.tombstones.iter().position(|t| t.is_orphan) {
                self.tombstones.remove(pos);
            } else {
                break;
            }
        }
    }

    /// Runs at the top of every ingest: posts with `expiresAt <= now`,
    /// tombstones with `retainUntil <= now`.
    pub fn prune_expired(&mut self, now: u64) {
        self.posts
            .retain(|p| !matches!(&p.wire, BoardWire::Post(post) if post.expires_at <= now));
        self.tombstones.retain(|t| t.retain_until_ms > now);
    }

    pub fn posts_for_geohash(&self, geohash: &str) -> Vec<&BoardWire> {
        self.posts
            .iter()
            .filter(|p| match &p.wire {
                BoardWire::Post(post) => post.geohash == geohash,
                _ => false,
            })
            .map(|p| &p.wire)
            .collect()
    }

    /// Gossip sync candidates (raw packets for the sync layer).
    pub fn sync_candidates(&self) -> Vec<BoardWire> {
        self.posts
            .iter()
            .map(|p| p.wire.clone())
            .chain(self.tombstones.iter().map(|t| {
                BoardWire::Tombstone(BoardTombstonePacket {
                    post_id: t.post_id.clone(),
                    author_signing_key: t.author_signing_key.clone(),
                    deleted_at: t.deleted_at_ms,
                    signature: Vec::new(),
                })
            }))
            .collect()
    }

    /// Persistence snapshot (Swift posts.json).
    pub fn to_records(&self) -> Vec<BoardRecord> {
        let mut out = Vec::new();
        for p in &self.posts {
            out.push(BoardRecord {
                post: Some(p.clone()),
                tombstone: None,
                retain_until: None,
            });
        }
        for t in &self.tombstones {
            out.push(BoardRecord {
                post: None,
                tombstone: Some(t.clone()),
                retain_until: Some(t.retain_until_ms),
            });
        }
        out
    }

    /// Panic wipe.
    pub fn wipe(&mut self) {
        self.posts.clear();
        self.tombstones.clear();
        self.seen_post_ids.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::board::BoardWire;
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::sync::Arc;

    fn clock_at(t: u64) -> (crate::noise::session_manager::Clock, Arc<AtomicU64>) {
        let cell = Arc::new(AtomicU64::new(t));
        let cell2 = cell.clone();
        let clock: crate::noise::session_manager::Clock =
            Arc::new(move || cell2.load(Ordering::SeqCst));
        (clock, cell)
    }

    fn post(id: u8, author: [u8; 32], created: u64) -> BoardPostPacket {
        BoardPostPacket {
            post_id: vec![id; 16],
            geohash: "abc".into(),
            content: format!("post {id}"),
            author_signing_key: author.to_vec(),
            author_nickname: "nick".into(),
            created_at: created,
            expires_at: created + 60_000,
            flags: 0,
            signature: Vec::new(),
        }
    }

    fn tomb(id: u8, author: [u8; 32], deleted_at: u64) -> BoardTombstonePacket {
        BoardTombstonePacket {
            post_id: vec![id; 16],
            author_signing_key: author.to_vec(),
            deleted_at,
            signature: Vec::new(),
        }
    }

    #[test]
    fn accepted_duplicate_rejected_lifecycle() {
        let (clock, _) = clock_at(1_000);
        let mut store = BoardStore::new(clock);
        let author = [7u8; 32];
        let p = post(1, author, 1_000);
        assert_eq!(
            store.ingest(&BoardWire::Post(p.clone())),
            BoardIngestResult::Accepted
        );
        assert_eq!(
            store.ingest(&BoardWire::Post(p.clone())),
            BoardIngestResult::Duplicate
        );
        // Expired: rejected.
        let (clock2, cell2) = clock_at(1_000 + 120_000);
        let mut store2 = BoardStore::new(clock2);
        assert_eq!(
            store2.ingest(&BoardWire::Post(post(2, author, 1_000))),
            BoardIngestResult::Rejected
        );
        let _ = cell2;
    }

    #[test]
    fn future_timestamps_rejected() {
        let (clock, _) = clock_at(1_000);
        let mut store = BoardStore::new(clock);
        let author = [7u8; 32];
        // created 2 h in the future (skew is 1 h).
        let future = post(3, author, 1_000 + 2 * 60 * 60 * 1000);
        assert_eq!(
            store.ingest(&BoardWire::Post(future)),
            BoardIngestResult::Rejected
        );
    }

    #[test]
    fn tombstone_deletes_and_blocks_reingest() {
        let (clock, _) = clock_at(1_000);
        let mut store = BoardStore::new(clock);
        let author = [7u8; 32];
        store.ingest(&BoardWire::Post(post(4, author, 1_000)));
        let tomb = tomb(4, author, 1_500);
        assert_eq!(
            store.ingest(&BoardWire::Tombstone(tomb.clone())),
            BoardIngestResult::Accepted
        );
        assert!(store.posts.is_empty());
        // The post cannot come back.
        assert_eq!(
            store.ingest(&BoardWire::Post(post(4, author, 1_000))),
            BoardIngestResult::Rejected
        );
        // Duplicate tombstone.
        assert_eq!(
            store.ingest(&BoardWire::Tombstone(tomb)),
            BoardIngestResult::Duplicate
        );
    }

    #[test]
    fn tombstone_from_wrong_author_rejected() {
        let (clock, _) = clock_at(1_000);
        let mut store = BoardStore::new(clock);
        let author = [7u8; 32];
        let other = [9u8; 32];
        store.ingest(&BoardWire::Post(post(5, author, 1_000)));
        let tomb = tomb(5, other, 1_500);
        assert_eq!(
            store.ingest(&BoardWire::Tombstone(tomb)),
            BoardIngestResult::Rejected
        );
        assert_eq!(store.posts.len(), 1);
    }

    #[test]
    fn per_author_cap_evicts_oldest() {
        let (clock, _) = clock_at(1_000);
        let mut store = BoardStore::new(clock);
        let author = [7u8; 32];
        for i in 0..7u8 {
            store.ingest(&BoardWire::Post(post(i, author, 1_000 + i as u64)));
        }
        let count = store
            .posts
            .iter()
            .filter(
                |p| matches!(&p.wire, BoardWire::Post(post) if post.author_signing_key == author),
            )
            .count();
        assert_eq!(count, MAX_POSTS_PER_AUTHOR);
        // The oldest (id 0,1) were evicted; id 2..6 remain.
        let ids: Vec<u8> = store
            .posts
            .iter()
            .filter_map(|p| match &p.wire {
                BoardWire::Post(post) if post.author_signing_key == author => Some(post.post_id[0]),
                _ => None,
            })
            .collect();
        assert_eq!(ids, vec![2, 3, 4, 5, 6]);
    }
}
