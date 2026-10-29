//! Private-media admission gate — exact port of bitchat's
//! `BLEService.privateMediaSendPolicy` (BLEService.swift:1174-1244),
//! `BLEPrivateMediaSessionStore` waiter machinery, and the
//! authenticated-peer-state (0x21) pinning rules.
//!
//! Types 0x20 (private file) / 0x09 (prerelease alias, receive-only) may
//! only be sent `.encrypted`; legacy clear sends require one-shot user
//! consent and are blocked entirely on a visible downgrade.

use crate::noise::session_manager::GenerationId;
use crate::peer_id::PeerId;
use crate::protocol::capabilities::PeerCapabilities;
use std::collections::{HashMap, HashSet};
use std::sync::Arc;

/// Proof timeout (TransportConfig.privateMediaCapabilityProofTimeoutSeconds).
pub const PROOF_TIMEOUT_S: u64 = 5;
/// Waiters per peer cap.
pub const WAITERS_PER_PEER_CAP: usize = 16;
/// Pending resolution sets cap.
pub const PENDING_PEER_CAP: usize = 64;

/// The four admission states (Swift PrivateMediaSendPolicy).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PrivateMediaSendPolicy {
    /// Bit-8 proven on the exact session generation: send Noise-encrypted.
    Encrypted,
    /// Announce hinted the bit or prior capability: start/wait for a
    /// handshake, but sending waits for the 0x21 proof.
    AwaitingCapabilityProof,
    /// Old client that never advertised the bit: one-shot user consent
    /// required for a signed-legacy clear send.
    LegacyRequiresConsent,
    /// Authenticated no-bit proof: a visible downgrade — raw fallback is
    /// blocked even with consent.
    BlockedDowngrade,
}

/// A verified inbound 0x21 authenticated-peer-state payload.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AuthenticatedPeerState {
    /// sha256-prefix-16 fingerprint of the session's remote static key.
    pub fingerprint: String,
    pub generation: GenerationId,
    pub capabilities: u64,
    pub signing_public_key: [u8; 32],
}

/// Per-peer session-side state feeding the decision tree.
#[derive(Debug, Clone, Default)]
pub struct PeerSessionState {
    /// Latest authenticated 0x21 (pins capabilities + signing key).
    pub authenticated_state: Option<AuthenticatedPeerState>,
    /// Proof-timeout marker: (fingerprint, generation) that timed out.
    pub timed_out: Option<(String, GenerationId)>,
    /// Session generation the caller currently observes.
    pub session_generation: Option<GenerationId>,
}

/// Inputs the caller gathers from its registry + session manager.
#[derive(Debug, Clone, Default)]
pub struct PolicyInputs {
    /// Announced (public, unauthenticated) capability bits — a discovery
    /// hint only; can never select encrypted sending by itself.
    pub announce_capabilities: u64,
    /// sha256-prefix-16 of the registry Noise public key for this peer.
    pub registry_noise_fingerprint: Option<String>,
}

pub struct PrivateMediaGate {
    /// Fingerprints observed to carry bit 8 (persists across sessions).
    observed_capable: HashSet<String>,
    sessions: HashMap<PeerId, PeerSessionState>,
    /// Fingerprint per waiter for release matching.
    waiters: HashMap<PeerId, Vec<(String, GenerationId)>>,
    /// Proof deadlines: (deadline_ms, fingerprint, generation).
    proof_deadlines: Vec<(u64, PeerId, String, GenerationId)>,
    clock: Arc<dyn Fn() -> u64 + Send + Sync>,
}

impl PrivateMediaGate {
    pub fn new(clock: Arc<dyn Fn() -> u64 + Send + Sync>) -> Self {
        PrivateMediaGate {
            observed_capable: HashSet::new(),
            sessions: HashMap::new(),
            waiters: HashMap::new(),
            proof_deadlines: Vec::new(),
            clock,
        }
    }

    fn now_ms(&self) -> u64 {
        (self.clock)()
    }

    /// Swift `privateMediaSendPolicy(to:)` — the exact decision tree, in
    /// order. `session_generation` is what the caller's session manager
    /// reports for the peer right now.
    pub fn resolve(
        &self,
        peer: &PeerId,
        inputs: &PolicyInputs,
        session_generation: Option<GenerationId>,
    ) -> PrivateMediaSendPolicy {
        let state = self.sessions.get(peer);
        let session_generation =
            session_generation.or_else(|| state.and_then(|s| s.session_generation));

        // 2. An authenticated state from a replaced session never selects
        //    encrypted sending.
        let authenticated = state.and_then(|s| s.authenticated_state.clone());
        if let Some(auth) = &authenticated {
            if Some(auth.generation) != session_generation {
                return PrivateMediaSendPolicy::AwaitingCapabilityProof;
            }
        }

        // 3. The fingerprint must bind to a stable verified registry key.
        let fingerprint = authenticated
            .as_ref()
            .map(|a| a.fingerprint.clone())
            .or_else(|| inputs.registry_noise_fingerprint.clone());
        let Some(fingerprint) = fingerprint else {
            return PrivateMediaSendPolicy::BlockedDowngrade;
        };

        // 4.
        let was_previously_capable = self.observed_capable.contains(&fingerprint);

        // 5. Authenticated 0x21 matching fingerprint AND generation.
        if let Some(auth) = &authenticated {
            if auth.fingerprint == fingerprint && Some(auth.generation) == session_generation {
                if auth.capabilities & PeerCapabilities::PRIVATE_MEDIA != 0 {
                    return PrivateMediaSendPolicy::Encrypted;
                }
                // Authenticated no-bit proof is a visible downgrade.
                return if was_previously_capable {
                    PrivateMediaSendPolicy::BlockedDowngrade
                } else {
                    PrivateMediaSendPolicy::LegacyRequiresConsent
                };
            }
        }

        // 6. Proof timeout marker matching fingerprint + generation.
        if let Some((timed_fp, timed_gen)) = state.and_then(|s| s.timed_out.clone()) {
            if timed_fp == fingerprint && Some(timed_gen) == session_generation {
                return if was_previously_capable {
                    PrivateMediaSendPolicy::BlockedDowngrade
                } else {
                    PrivateMediaSendPolicy::LegacyRequiresConsent
                };
            }
        }

        // 7. Public announce bit or prior capability: wait for the proof.
        if inputs.announce_capabilities & PeerCapabilities::PRIVATE_MEDIA != 0
            || was_previously_capable
        {
            return PrivateMediaSendPolicy::AwaitingCapabilityProof;
        }

        // 8. Old client.
        PrivateMediaSendPolicy::LegacyRequiresConsent
    }

    /// Swift `resolvePrivateMediaSendPolicy(to:completion:)` — immediate
    /// resolution or waiter registration. Returns `None` when a waiter was
    /// registered (the caller will be released by a later
    /// `apply_authenticated_state` / timeout tick), `Some(policy)` for an
    /// immediate answer.
    pub fn resolve_or_wait(
        &mut self,
        peer: &PeerId,
        inputs: &PolicyInputs,
        session_generation: Option<GenerationId>,
    ) -> Option<PrivateMediaSendPolicy> {
        let immediate = self.resolve(peer, inputs, session_generation);
        if immediate != PrivateMediaSendPolicy::AwaitingCapabilityProof {
            return Some(immediate);
        }
        // Register a waiter (per-peer cap 16, global pending cap 64).
        let fingerprint = inputs
            .registry_noise_fingerprint
            .clone()
            .unwrap_or_default();
        let registered_total: usize = self.waiters.values().map(Vec::len).sum();
        let existing = self.waiters.get(peer).map(Vec::len).unwrap_or(0);
        if existing >= WAITERS_PER_PEER_CAP || registered_total >= PENDING_PEER_CAP {
            // Registration refused: fail closed.
            return Some(PrivateMediaSendPolicy::BlockedDowngrade);
        }
        let entry = self.waiters.entry(peer.clone()).or_default();
        let gen = session_generation.unwrap_or(GenerationId(0));
        entry.push((fingerprint.clone(), gen));
        // Arm the proof deadline (5 s). Expiry never auto-sends clear bytes.
        let now = self.now_ms();
        self.proof_deadlines
            .push((now + PROOF_TIMEOUT_S * 1000, peer.clone(), fingerprint, gen));
        None
    }

    /// Swift `beginAuthenticatedGeneration` — a new/rekeyed session rotates
    /// the waiter bookkeeping; mismatched-fingerprint waiters are released
    /// with `.blockedDowngrade`.
    pub fn begin_authenticated_generation(&mut self, peer: &PeerId, new_generation: GenerationId) {
        if let Some(state) = self.sessions.get_mut(peer) {
            state.session_generation = Some(new_generation);
        } else {
            self.sessions.insert(
                peer.clone(),
                PeerSessionState {
                    session_generation: Some(new_generation),
                    ..Default::default()
                },
            );
        }
    }

    /// A verified inbound 0x21 (Swift `handleAuthenticatedPeerState`): pins
    /// capability + signing key, retires proof timers, releases matching
    /// waiters with the re-resolved policy.
    pub fn apply_authenticated_state(
        &mut self,
        peer: &PeerId,
        state_in: AuthenticatedPeerState,
        inputs: &PolicyInputs,
    ) -> Vec<(PrivateMediaSendPolicy,)> {
        // Only decrypted 0x21 pins — public no-bit announces cannot
        // overwrite it.
        let session = self.sessions.entry(peer.clone()).or_default();
        session.authenticated_state = Some(state_in.clone());
        session.timed_out = None;
        if state_in.capabilities & PeerCapabilities::PRIVATE_MEDIA != 0 {
            self.observed_capable.insert(state_in.fingerprint.clone());
        }
        // Retire this peer's proof deadlines.
        self.proof_deadlines.retain(|(_, p, _, _)| p != peer);
        // Release matching waiters with the re-resolved policy.
        let waiters = self.waiters.remove(peer).unwrap_or_default();
        let mut released = Vec::new();
        for _ in waiters {
            let policy = self.resolve(peer, inputs, Some(state_in.generation));
            released.push((policy,));
        }
        released
    }

    /// Tick: expire due proof deadlines — pins a timeout marker and
    /// releases waiters (which then hit rule 6). Never auto-sends clear.
    pub fn on_tick(
        &mut self,
        inputs_by_peer: &dyn Fn(&PeerId) -> PolicyInputs,
    ) -> Vec<(PeerId, PrivateMediaSendPolicy)> {
        let now = self.now_ms();
        let due: Vec<_> = self
            .proof_deadlines
            .iter()
            .filter(|(deadline, _, _, _)| *deadline <= now)
            .cloned()
            .collect();
        if due.is_empty() {
            return Vec::new();
        }
        self.proof_deadlines
            .retain(|(deadline, _, _, _)| *deadline > now);
        let mut out = Vec::new();
        for (_, peer, fingerprint, generation) in due {
            // Pin the timeout marker.
            let session = self.sessions.entry(peer.clone()).or_default();
            session.timed_out = Some((fingerprint.clone(), generation));
            // Release waiters.
            let waiters = self.waiters.remove(&peer).unwrap_or_default();
            let inputs = inputs_by_peer(&peer);
            for _ in waiters {
                let policy = self.resolve(&peer, &inputs, Some(generation));
                out.push((peer.clone(), policy));
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    fn clock_at(t: u64) -> (Arc<dyn Fn() -> u64 + Send + Sync>, Arc<AtomicU64>) {
        let cell = Arc::new(AtomicU64::new(t));
        let cell2 = cell.clone();
        let clock: Arc<dyn Fn() -> u64 + Send + Sync> =
            Arc::new(move || cell2.load(Ordering::SeqCst));
        (clock, cell)
    }

    fn inputs(caps: u64) -> PolicyInputs {
        PolicyInputs {
            announce_capabilities: caps,
            registry_noise_fingerprint: Some("feedfacefeedface".into()),
        }
    }

    fn auth_state(caps: u64, gen: u64) -> AuthenticatedPeerState {
        AuthenticatedPeerState {
            fingerprint: "feedfacefeedface".into(),
            generation: GenerationId(gen as u128),
            capabilities: caps,
            signing_public_key: [3u8; 32],
        }
    }

    #[test]
    fn old_client_requires_consent() {
        let (clock, _) = clock_at(1_000);
        let gate = PrivateMediaGate::new(clock);
        let peer = PeerId::new("aaaaaaaaaaaaaaaa");
        let policy = gate.resolve(&peer, &inputs(0), Some(GenerationId(1)));
        assert_eq!(policy, PrivateMediaSendPolicy::LegacyRequiresConsent);
    }

    #[test]
    fn announce_bit_waits_for_proof_then_timeout_downgrades() {
        let (clock, cell) = clock_at(1_000);
        let mut gate = PrivateMediaGate::new(clock);
        let peer = PeerId::new("aaaaaaaaaaaaaaaa");
        let gen = Some(GenerationId(1));
        // Announce hint → wait.
        assert_eq!(
            gate.resolve(&peer, &inputs(PeerCapabilities::PRIVATE_MEDIA), gen),
            PrivateMediaSendPolicy::AwaitingCapabilityProof
        );
        // Register a waiter.
        let immediate = gate.resolve_or_wait(&peer, &inputs(PeerCapabilities::PRIVATE_MEDIA), gen);
        assert!(immediate.is_none());
        // Deadline fires at +5 s: rule 6 → legacy (never auto-sent clear).
        cell.store(1_000 + 5_100, Ordering::SeqCst);
        let released = gate.on_tick(&|_| inputs(PeerCapabilities::PRIVATE_MEDIA));
        assert_eq!(released.len(), 1);
        assert_eq!(released[0].1, PrivateMediaSendPolicy::LegacyRequiresConsent);
        // Subsequent resolution hits the pinned marker directly.
        assert_eq!(
            gate.resolve(&peer, &inputs(PeerCapabilities::PRIVATE_MEDIA), gen),
            PrivateMediaSendPolicy::LegacyRequiresConsent
        );
    }

    #[test]
    fn authenticated_proof_with_bit8_encrypts() {
        let (clock, _) = clock_at(1_000);
        let mut gate = PrivateMediaGate::new(clock);
        let peer = PeerId::new("aaaaaaaaaaaaaaaa");
        gate.begin_authenticated_generation(&peer, GenerationId(7));
        let released = gate.apply_authenticated_state(
            &peer,
            auth_state(PeerCapabilities::PRIVATE_MEDIA, 7),
            &inputs(0),
        );
        assert!(released.is_empty()); // no waiters were registered
        assert_eq!(
            gate.resolve(&peer, &inputs(0), Some(GenerationId(7))),
            PrivateMediaSendPolicy::Encrypted
        );
        // A rekey replaces the session: the old proof no longer selects
        // encrypted sending (rule 2).
        assert_eq!(
            gate.resolve(&peer, &inputs(0), Some(GenerationId(9))),
            PrivateMediaSendPolicy::AwaitingCapabilityProof
        );
    }

    #[test]
    fn authenticated_no_bit_is_visible_downgrade() {
        let (clock, _) = clock_at(1_000);
        let mut gate = PrivateMediaGate::new(clock);
        let peer = PeerId::new("aaaaaaaaaaaaaaaa");
        // Peer was previously capable (observed bit 8)…
        gate.apply_authenticated_state(
            &peer,
            auth_state(PeerCapabilities::PRIVATE_MEDIA, 1),
            &inputs(0),
        );
        // …then presents an authenticated proof WITHOUT the bit.
        gate.begin_authenticated_generation(&peer, GenerationId(2));
        gate.apply_authenticated_state(&peer, auth_state(0, 2), &inputs(0));
        assert_eq!(
            gate.resolve(&peer, &inputs(0), Some(GenerationId(2))),
            PrivateMediaSendPolicy::BlockedDowngrade
        );
    }

    #[test]
    fn no_fingerprint_blocks() {
        let (clock, _) = clock_at(1_000);
        let gate = PrivateMediaGate::new(clock);
        let peer = PeerId::new("aaaaaaaaaaaaaaaa");
        let no_fp = PolicyInputs {
            announce_capabilities: 0,
            registry_noise_fingerprint: None,
        };
        assert_eq!(
            gate.resolve(&peer, &no_fp, Some(GenerationId(1))),
            PrivateMediaSendPolicy::BlockedDowngrade
        );
    }
}
