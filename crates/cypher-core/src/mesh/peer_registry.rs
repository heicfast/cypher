//! Peer registry — the lock-backed store of known peers (bitchat's
//! `BLEPeerRegistry` / `BLEPeerRegistryStore`).

use std::collections::HashMap;

use crate::consts;
use crate::protocol::capabilities::PeerCapabilities;

/// Everything the engine tracks about a known peer.
#[derive(Clone, Debug, serde::Serialize)]
pub struct PeerInfo {
    pub peer_id: crate::PeerId,
    pub nickname: String,
    pub noise_public_key: [u8; 32],
    pub signing_public_key: [u8; 32],
    pub is_connected: bool,
    /// Last time a verified announce (or any packet) was attributed.
    pub last_seen_ms: u64,
    /// Trust-pinned (TOFU) signing key.
    pub pinned_signing_key: Option<[u8; 32]>,
    pub capabilities: Option<PeerCapabilities>,
    pub is_verified: bool,
    pub is_favorite: bool,
}

#[derive(Default)]
pub struct PeerRegistry {
    peers: HashMap<String, PeerInfo>,
}

impl PeerRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    /// Insert or update a peer from a **verified** announce. Returns the
    /// previous entry when the signing key changed (key mismatch is the
    /// caller's business — TOFU refuses replacements).
    pub fn upsert_verified(
        &mut self,
        peer_id: crate::PeerId,
        nickname: String,
        noise_public_key: [u8; 32],
        signing_public_key: [u8; 32],
        capabilities: Option<PeerCapabilities>,
        now_ms: u64,
    ) -> &mut PeerInfo {
        let key = peer_id.as_str().to_string();
        let entry = self.peers.entry(key).or_insert_with(|| PeerInfo {
            peer_id: peer_id.clone(),
            nickname: nickname.clone(),
            noise_public_key,
            signing_public_key,
            is_connected: false,
            last_seen_ms: now_ms,
            pinned_signing_key: Some(signing_public_key),
            capabilities,
            is_verified: true,
            is_favorite: false,
        });
        entry.nickname = nickname;
        entry.noise_public_key = noise_public_key;
        entry.signing_public_key = signing_public_key;
        entry.capabilities = capabilities;
        entry.last_seen_ms = now_ms;
        entry.is_verified = true;
        entry
    }

    pub fn get(&self, peer_id: &crate::PeerId) -> Option<&PeerInfo> {
        self.peers.get(peer_id.as_str())
    }

    pub fn get_mut(&mut self, peer_id: &crate::PeerId) -> Option<&mut PeerInfo> {
        self.peers.get_mut(peer_id.as_str())
    }

    pub fn touch(&mut self, peer_id: &crate::PeerId, now_ms: u64) {
        if let Some(entry) = self.peers.get_mut(peer_id.as_str()) {
            entry.last_seen_ms = now_ms;
        }
    }

    pub fn set_connected(&mut self, peer_id: &crate::PeerId, connected: bool) {
        if let Some(entry) = self.peers.get_mut(peer_id.as_str()) {
            entry.is_connected = connected;
        }
    }

    pub fn remove(&mut self, peer_id: &crate::PeerId) -> Option<PeerInfo> {
        self.peers.remove(peer_id.as_str())
    }

    pub fn connected_peers(&self) -> Vec<crate::PeerId> {
        self.peers
            .values()
            .filter(|p| p.is_connected)
            .map(|p| p.peer_id.clone())
            .collect()
    }

    pub fn degree(&self) -> usize {
        self.peers.values().filter(|p| p.is_connected).count()
    }

    pub fn all(&self) -> impl Iterator<Item = &PeerInfo> {
        self.peers.values()
    }

    /// Connectivity pass: connected peers not seen for 8 s with no live links
    /// are marked disconnected; disconnected peers past the retention window
    /// (60 s verified / 45 s unverified) are removed.
    pub fn reconcile(
        &mut self,
        now_ms: u64,
        live_peers: &[crate::PeerId],
    ) -> (Vec<crate::PeerId>, Vec<crate::PeerId>) {
        let inactivity = (consts::BLE_PEER_INACTIVITY_TIMEOUT_S * 1000.0) as u64;
        let retention_verified = (consts::BLE_PEER_RETENTION_VERIFIED_S * 1000.0) as u64;
        let retention_unverified = (consts::BLE_PEER_RETENTION_UNVERIFIED_S * 1000.0) as u64;

        let mut disconnected = Vec::new();
        let mut removed = Vec::new();

        for info in self.peers.values_mut() {
            if info.is_connected
                && now_ms.saturating_sub(info.last_seen_ms) > inactivity
                && !live_peers.contains(&info.peer_id)
            {
                info.is_connected = false;
                disconnected.push(info.peer_id.clone());
            }
        }

        let stale: Vec<String> = self
            .peers
            .values()
            .filter(|p| {
                let retention = if p.is_verified || p.is_favorite {
                    retention_verified
                } else {
                    retention_unverified
                };
                !p.is_connected && now_ms.saturating_sub(p.last_seen_ms) > retention
            })
            .map(|p| p.peer_id.as_str().to_string())
            .collect();
        for key in stale {
            if let Some(info) = self.peers.remove(&key) {
                removed.push(info.peer_id);
            }
        }

        (disconnected, removed)
    }

    pub fn len(&self) -> usize {
        self.peers.len()
    }

    pub fn is_empty(&self) -> bool {
        self.peers.is_empty()
    }

    pub fn clear(&mut self) {
        self.peers.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn upsert(registry: &mut PeerRegistry, hex: &str, now: u64) -> crate::PeerId {
        let peer = crate::PeerId::new(hex);
        registry.upsert_verified(
            peer.clone(),
            format!("peer-{hex}"),
            [0xAA; 32],
            [0xBB; 32],
            Some(PeerCapabilities::local_supported()),
            now,
        );
        peer
    }

    #[test]
    fn upsert_and_query() {
        let mut r = PeerRegistry::new();
        let p = upsert(&mut r, "0011223344556677", 0);
        assert_eq!(r.degree(), 0);
        r.set_connected(&p, true);
        assert_eq!(r.degree(), 1);
        assert_eq!(r.get(&p).unwrap().nickname, "peer-0011223344556677");
    }

    #[test]
    fn inactivity_marks_disconnected() {
        let mut r = PeerRegistry::new();
        let p = upsert(&mut r, "0011223344556677", 0);
        r.set_connected(&p, true);
        let (disconnected, removed) = r.reconcile(9_000, &[]);
        assert_eq!(disconnected.len(), 1);
        assert!(removed.is_empty());
        assert!(!r.get(&p).unwrap().is_connected);
    }

    #[test]
    fn retention_removes_stale() {
        let mut r = PeerRegistry::new();
        let p = upsert(&mut r, "0011223344556677", 0);
        let (_, removed) = r.reconcile(61_000, &[]);
        assert_eq!(removed, vec![p.clone()]);
        assert!(r.get(&p).is_none());
    }

    #[test]
    fn live_peers_survive_reconcile() {
        let mut r = PeerRegistry::new();
        let p = upsert(&mut r, "0011223344556677", 0);
        r.set_connected(&p, true);
        let (d, rm) = r.reconcile(9_000, std::slice::from_ref(&p));
        assert!(d.is_empty() && rm.is_empty());
        assert!(r.get(&p).unwrap().is_connected);
    }
}
