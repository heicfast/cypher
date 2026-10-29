//! Incoming media disk quota — exact port of bitchat's
//! `BLEIncomingFileStore` (bitchat/Services/BLE/BLEIncomingFileStore.swift).
//!
//! Sans-I/O adaptation: the store tracks metadata and returns the set of
//! files the host must delete (`Vec<MediaFileId>`); actual filesystem
//! work stays with the host layer.

use crate::consts;
use std::collections::HashSet;

pub const DEFAULT_QUOTA_BYTES: u64 = 100 * 1024 * 1024;
pub const DEFAULT_MEDIA_RETENTION_S: u64 = 7 * 24 * 60 * 60;
/// In-flight live voice captures are never evicted (they still count).
pub const LIVE_CAPTURE_PREFIX: &str = "voice_live_";

pub type MediaFileId = String;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MediaFile {
    pub id: MediaFileId,
    pub category: MediaCategory,
    /// Incoming side only — outgoing never competes for this quota.
    pub incoming: bool,
    pub size_bytes: u64,
    pub modified_ms: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum MediaCategory {
    VoiceNotes,
    Images,
    Files,
}

impl MediaCategory {
    fn as_str(&self) -> &'static str {
        match self {
            MediaCategory::VoiceNotes => "voicenotes",
            MediaCategory::Images => "images",
            MediaCategory::Files => "files",
        }
    }
}

/// Result of a quota enforcement run.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct QuotaActions {
    /// Oldest-modified first, to be deleted by the host.
    pub evict: Vec<MediaFileId>,
}

#[derive(Clone, Default)]
pub struct IncomingFileQuota {
    quota_bytes: u64,
    retention_s: u64,
    /// Live captures and pending deliveries are exempt from eviction.
    deletion_reservations: HashSet<MediaFileId>,
    pending_deliveries: HashSet<MediaFileId>,
}

impl IncomingFileQuota {
    pub fn new() -> Self {
        IncomingFileQuota {
            quota_bytes: DEFAULT_QUOTA_BYTES,
            retention_s: DEFAULT_MEDIA_RETENTION_S,
            deletion_reservations: HashSet::new(),
            pending_deliveries: HashSet::new(),
        }
    }

    pub fn reserve(&mut self, id: &str) {
        self.deletion_reservations.insert(id.to_string());
    }

    pub fn unreserve(&mut self, id: &str) {
        self.deletion_reservations.remove(id);
    }

    pub fn mark_pending_delivery(&mut self, id: &str) {
        self.pending_deliveries.insert(id.to_string());
    }

    pub fn clear_pending_delivery(&mut self, id: &str) {
        self.pending_deliveries.remove(id);
    }

    fn protected(&self, f: &MediaFile) -> bool {
        f.id.starts_with(LIVE_CAPTURE_PREFIX)
            || self.deletion_reservations.contains(&f.id)
            || self.pending_deliveries.contains(&f.id)
    }

    /// Swift `enforceQuota(reservingBytes:)` — must be called before every
    /// save; live voice reserves the full worst case (384 KiB) up front.
    pub fn enforce_quota(&self, incoming: &[MediaFile], reserving_bytes: u64) -> QuotaActions {
        // Scan only the three */incoming dirs.
        let current_usage: u64 = incoming
            .iter()
            .filter(|f| f.incoming)
            .map(|f| f.size_bytes)
            .sum();
        let target = self.quota_bytes.saturating_sub(reserving_bytes);
        if current_usage <= target {
            return QuotaActions::default();
        }
        let need_to_free = current_usage - target;
        let mut freed = 0u64;
        let mut evict = Vec::new();
        // Oldest-modified first; never evict live captures or reservations
        // (they still count toward usage).
        let mut candidates: Vec<&MediaFile> = incoming
            .iter()
            .filter(|f| f.incoming && !self.protected(f))
            .collect();
        candidates.sort_by_key(|f| f.modified_ms);
        for f in candidates {
            if freed >= need_to_free {
                break;
            }
            freed += f.size_bytes;
            evict.push(f.id.clone());
        }
        QuotaActions { evict }
    }

    /// Swift `expireAgedMedia(retention:)` — deletes `modified < now -
    /// retention` across all six subdirs, honoring the same exclusions.
    /// Incoming and outgoing both age out here.
    pub fn expire_aged(&self, files: &[MediaFile], now_ms: u64) -> QuotaActions {
        let cutoff = now_ms.saturating_sub(self.retention_s * 1000);
        QuotaActions {
            evict: files
                .iter()
                .filter(|f| f.modified_ms < cutoff && !self.protected(f))
                .map(|f| f.id.clone())
                .collect(),
        }
    }

    /// Convenience for the host: sweep stale live captures from a previous
    /// session (Swift `sweepStaleLiveCaptures` deletes `voice_live_*`
    /// orphans at startup — before any reservation exists).
    pub fn sweep_stale_live_captures(&self, files: &[MediaFile]) -> QuotaActions {
        QuotaActions {
            evict: files
                .iter()
                .filter(|f| f.incoming && f.id.starts_with(LIVE_CAPTURE_PREFIX))
                .map(|f| f.id.clone())
                .collect(),
        }
    }

    pub fn category_dir(category: MediaCategory, incoming: bool) -> String {
        format!(
            "{}/{}/{}",
            consts::FILE_STORE_ROOT,
            category.as_str(),
            if incoming { "incoming" } else { "outgoing" }
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn file(id: &str, size: u64, modified_ms: u64) -> MediaFile {
        MediaFile {
            id: id.to_string(),
            category: MediaCategory::Files,
            incoming: true,
            size_bytes: size,
            modified_ms,
        }
    }

    #[test]
    fn under_quota_no_eviction() {
        let quota = IncomingFileQuota::new();
        let files = vec![file("a", 1000, 1), file("b", 2000, 2)];
        let actions = quota.enforce_quota(&files, 0);
        assert!(actions.evict.is_empty());
    }

    #[test]
    fn evicts_oldest_modified_first() {
        let quota = IncomingFileQuota::new();
        // 3 files totaling 300; quota 100 with 150 reserved → target 250,
        // usage 300 → free 50 → evict oldest (100 B) only.
        let files = vec![
            file("newest", 100, 300),
            file("middle", 100, 200),
            file("oldest", 100, 100),
        ];
        let actions = quota.enforce_quota(&files, 0);
        // Over-quota case needs the store quota to be small: default is
        // 100 MiB, so exercise via a direct calculation instead.
        let _ = actions;
        // Direct: simulate a 300-byte store with a 250-byte target.
        let mut small = IncomingFileQuota::new();
        small.quota_bytes = 300;
        let actions = small.enforce_quota(&files, 50);
        assert_eq!(actions.evict, vec!["oldest".to_string()]);
    }

    #[test]
    fn live_captures_never_evicted_but_still_count() {
        let mut small = IncomingFileQuota::new();
        small.quota_bytes = 300;
        let files = vec![
            file("voice_live_x", 100, 100),
            file("plain", 100, 200),
            file("plain2", 100, 300),
        ];
        // Target 250 → free 50: `plain` (oldest non-protected) goes even
        // though `voice_live_x` is older.
        let actions = small.enforce_quota(&files, 50);
        assert_eq!(actions.evict, vec!["plain".to_string()]);
        // The live capture still counts toward usage: with reservations
        // covering everything, eviction can only pick what's unprotected.
        let mut quota = small.clone();
        quota.reserve("plain");
        quota.reserve("plain2");
        let actions = quota.enforce_quota(&files, 50);
        // Nothing evictable — the caller must handle over-quota by
        // rejecting the incoming save (fail closed upstream).
        assert!(actions.evict.is_empty());
    }

    #[test]
    fn aged_media_expires_past_retention() {
        let quota = IncomingFileQuota::new();
        let now = 10_000_000_000;
        let fresh = file("fresh", 10, now - 1000);
        let stale = file("stale", 10, now - (DEFAULT_MEDIA_RETENTION_S * 1000 + 1));
        let actions = quota.expire_aged(&[fresh, stale], now);
        assert_eq!(actions.evict, vec!["stale".to_string()]);
    }

    #[test]
    fn sweep_removes_only_live_captures() {
        let quota = IncomingFileQuota::new();
        let files = vec![
            file("voice_live_a", 10, 1),
            file("voice_b", 10, 1),
            file("img_c", 10, 1),
        ];
        let actions = quota.sweep_stale_live_captures(&files);
        assert_eq!(actions.evict, vec!["voice_live_a".to_string()]);
    }
}
