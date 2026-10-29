//! Message deduplication (LRU: 300 s max age, 1000 entries, trim to 75%).

use std::collections::HashMap;

use crate::consts;

pub struct MessageDeduplicator {
    entries: HashMap<String, u64>, // message id -> first-seen ms
    order: Vec<String>,            // FIFO for eviction
    max_age_ms: u64,
    max_count: usize,
}

impl Default for MessageDeduplicator {
    fn default() -> Self {
        Self::new(consts::DEDUP_MAX_AGE_S * 1000, consts::DEDUP_MAX_COUNT)
    }
}

impl MessageDeduplicator {
    pub fn new(max_age_ms: u64, max_count: usize) -> Self {
        MessageDeduplicator {
            entries: HashMap::new(),
            order: Vec::new(),
            max_age_ms,
            max_count,
        }
    }

    /// True when `id` was already seen (within the window).
    pub fn is_duplicate(&mut self, id: &str, now_ms: u64) -> bool {
        self.cleanup(now_ms);
        self.entries.contains_key(id)
    }

    /// Record an id as seen.
    pub fn record(&mut self, id: &str, now_ms: u64) {
        if self.entries.contains_key(id) {
            return;
        }
        self.entries.insert(id.to_string(), now_ms);
        self.order.push(id.to_string());
        self.enforce_capacity(now_ms);
    }

    /// Check-and-record in one step; returns whether it was a duplicate.
    pub fn check_and_record(&mut self, id: &str, now_ms: u64) -> bool {
        let dup = self.is_duplicate(id, now_ms);
        if !dup {
            self.record(id, now_ms);
        }
        dup
    }

    /// Drop entries older than the max age.
    pub fn cleanup(&mut self, now_ms: u64) {
        let cutoff = now_ms.saturating_sub(self.max_age_ms);
        self.entries.retain(|_, seen| *seen >= cutoff);
        self.order.retain(|id| self.entries.contains_key(id));
    }

    fn enforce_capacity(&mut self, now_ms: u64) {
        let limit = (self.max_count as f64 * consts::DEDUP_TRIM_FACTOR) as usize;
        if self.entries.len() > self.max_count {
            // Evict oldest (FIFO) down to 75% capacity.
            while self.entries.len() > limit && !self.order.is_empty() {
                let oldest = self.order.remove(0);
                self.entries.remove(&oldest);
            }
            let _ = now_ms;
        }
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub fn clear(&mut self) {
        self.entries.clear();
        self.order.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn duplicate_detection() {
        let mut d = MessageDeduplicator::default();
        assert!(!d.check_and_record("m1", 1000));
        assert!(d.check_and_record("m1", 1000));
        assert!(!d.check_and_record("m2", 1000));
    }

    #[test]
    fn age_window() {
        let mut d = MessageDeduplicator::default();
        d.check_and_record("m1", 0);
        // Still within 300 s.
        assert!(d.check_and_record("m1", 299_000));
        // Beyond 300 s → forgotten (no longer a duplicate).
        assert!(!d.check_and_record("m1", 301_000));
    }

    #[test]
    fn capacity_trim() {
        let mut d = MessageDeduplicator::new(u64::MAX, 10);
        for i in 0..12 {
            d.check_and_record(&format!("m{i}"), 0);
        }
        // Trimmed to 75% of 10.
        assert!(d.len() <= 8, "len = {}", d.len());
        // Oldest entries evicted first.
        assert!(!d.entries.contains_key("m0"));
        assert!(d.entries.contains_key("m11"));
    }
}
