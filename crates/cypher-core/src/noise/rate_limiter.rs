//! `NoiseRateLimiter` — global-first, then per-peer, sliding-window limits.
//!
//! * Handshakes: global ≥ 30/min rejected first, then per-peer ≥ 10/min
//!   (60-second window).
//! * Messages: global ≥ 500/s rejected first, then per-peer ≥ 100/s
//!   (1-second window).

use std::collections::HashMap;

use crate::consts;
use crate::PeerId;

pub struct NoiseRateLimiter {
    handshake_timestamps: HashMap<String, Vec<f64>>,
    message_timestamps: HashMap<String, Vec<f64>>,
    global_handshake_timestamps: Vec<f64>,
    global_message_timestamps: Vec<f64>,
    now_ms: Box<dyn Fn() -> u64 + Send + Sync>,
}

impl Default for NoiseRateLimiter {
    fn default() -> Self {
        Self::new()
    }
}

impl NoiseRateLimiter {
    pub fn new() -> Self {
        Self::with_clock(Box::new(crate::util::now_ms))
    }

    pub fn with_clock(now_ms: Box<dyn Fn() -> u64 + Send + Sync>) -> Self {
        NoiseRateLimiter {
            handshake_timestamps: HashMap::new(),
            message_timestamps: HashMap::new(),
            global_handshake_timestamps: Vec::new(),
            global_message_timestamps: Vec::new(),
            now_ms,
        }
    }

    fn now_s(&self) -> f64 {
        (self.now_ms)() as f64 / 1000.0
    }

    pub fn allow_handshake(&mut self, peer_id: &PeerId) -> bool {
        let now = self.now_s();
        let window_start = now - 60.0;
        // Global limit first.
        self.global_handshake_timestamps
            .retain(|&t| t > window_start);
        if self.global_handshake_timestamps.len() >= consts::NOISE_MAX_GLOBAL_HANDSHAKES_PER_MIN {
            return false;
        }
        // Then per-peer.
        let timestamps = self
            .handshake_timestamps
            .entry(peer_id.as_str().to_string())
            .or_default();
        timestamps.retain(|&t| t > window_start);
        if timestamps.len() >= consts::NOISE_MAX_HANDSHAKES_PER_MIN_PER_PEER {
            return false;
        }
        timestamps.push(now);
        self.global_handshake_timestamps.push(now);
        true
    }

    pub fn allow_message(&mut self, peer_id: &PeerId) -> bool {
        let now = self.now_s();
        let window_start = now - 1.0;
        self.global_message_timestamps.retain(|&t| t > window_start);
        if self.global_message_timestamps.len() >= consts::NOISE_MAX_GLOBAL_MESSAGES_PER_S {
            return false;
        }
        let timestamps = self
            .message_timestamps
            .entry(peer_id.as_str().to_string())
            .or_default();
        timestamps.retain(|&t| t > window_start);
        if timestamps.len() >= consts::NOISE_MAX_MESSAGES_PER_S_PER_PEER {
            return false;
        }
        timestamps.push(now);
        self.global_message_timestamps.push(now);
        true
    }

    pub fn reset(&mut self, peer_id: &PeerId) {
        self.handshake_timestamps.remove(peer_id.as_str());
        self.message_timestamps.remove(peer_id.as_str());
    }

    pub fn reset_all(&mut self) {
        self.handshake_timestamps.clear();
        self.message_timestamps.clear();
        self.global_handshake_timestamps.clear();
        self.global_message_timestamps.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::sync::Arc;

    fn with_fake_clock(start_ms: u64) -> (NoiseRateLimiter, Arc<AtomicU64>) {
        let clock = Arc::new(AtomicU64::new(start_ms));
        let c2 = clock.clone();
        let rl = NoiseRateLimiter::with_clock(Box::new(move || c2.load(Ordering::SeqCst)));
        (rl, clock)
    }

    #[test]
    fn per_peer_handshake_limit() {
        let (mut rl, clock) = with_fake_clock(1_000_000);
        let peer = PeerId::new("0011223344556677");
        for _ in 0..10 {
            assert!(rl.allow_handshake(&peer));
        }
        assert!(
            !rl.allow_handshake(&peer),
            "11th handshake within a minute rejected"
        );
        // Advance past the window.
        clock.fetch_add(61_000, Ordering::SeqCst);
        assert!(rl.allow_handshake(&peer));
    }

    #[test]
    fn global_handshake_limit() {
        let (mut rl, clock) = with_fake_clock(1_000_000);
        for i in 0..30 {
            assert!(rl.allow_handshake(&PeerId::new(format!("{i:016x}"))));
        }
        assert!(!rl.allow_handshake(&PeerId::new("ffeeddccbbaa9988")));
        clock.fetch_add(61_000, Ordering::SeqCst);
        assert!(rl.allow_handshake(&PeerId::new("ffeeddccbbaa9988")));
    }

    #[test]
    fn per_peer_message_limit_and_reset() {
        let (mut rl, _clock) = with_fake_clock(1_000_000);
        let peer = PeerId::new("aabbccddeeff0011");
        for _ in 0..100 {
            assert!(rl.allow_message(&peer));
        }
        assert!(!rl.allow_message(&peer));
        rl.reset_all();
        assert!(rl.allow_message(&peer));
    }
}
