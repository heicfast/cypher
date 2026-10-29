//! Noise session manager — exact port of bitchat's `NoiseSessionManager`
//! (bitchat/Noise/NoiseSessionManager.swift, 1289 lines).
//!
//! Owns the per-peer session table with:
//! * quarantine of an established session on an unauthenticated fresh
//!   initiation (message 1), keeping the old transport **receive-only**;
//! * bounded rollback (restore) of the quarantined session when the
//!   replacement handshake fails or times out, under a 60 s rollback
//!   cooldown;
//! * deterministic crossed-initiation resolution — the lexicographically
//!   smaller short peer ID keeps the initiator role;
//! * generation lease tracking: an opaque id per exact session entry,
//!   checked by encrypt/decrypt-with-generation so a rekey between
//!   capability proof and use fails closed;
//! * one-shot handshake initiation claim tokens;
//! * manager-owned handshake recovery (one bounded convergence retry per
//!   rollback), with rate-limit redispatch.
//!
//! Sans-I/O adaptation: Swift's DispatchQueues/timers become deadline
//! records processed by [`SessionManager::handle_deadlines`], driven by
//! the engine clock. All barrier-protected multi-step transitions in the
//! Swift original are single `&mut self` sections here — the engine runs
//! one thread per instance, which provides the same serialization.

use crate::consts;
use crate::noise::handshake::NoiseRole;
use crate::noise::session::NoiseSession;
use crate::noise::NoiseError;
use crate::PeerId;
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use x25519_dalek::StaticSecret;

/// Opaque per-session-entry id (Swift: `UUID`). Never sent on the wire;
/// only compared for exact identity, so a monotonic counter suffices.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct GenerationId(pub u128);

impl std::fmt::Display for GenerationId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{:032x}", self.0)
    }
}

/// One-shot token binding a prepared-but-unsent message 1 to its session.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct AttemptId(pub u128);

/// Identity of a recovery request (Swift: fileprivate `recoveryID`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct RecoveryId(pub u128);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct CallbackId(pub u128);

/// Per-table identity of a session entry (Swift: object identity guards).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct Epoch(pub u64);

pub type Clock = Arc<dyn Fn() -> u64 + Send + Sync>;

/// Configuration (Swift: `NoiseSecurityConstants` + injectable init params).
#[derive(Debug, Clone)]
pub struct ManagerConfig {
    /// Initiator bound, seconds.
    pub handshake_timeout_s: f64,
    /// Responder window and quarantine rollback deadline, seconds.
    pub responder_timeout_s: f64,
    /// Crossed-initiation reorder grace after initiator completion, seconds.
    pub initiator_grace_s: f64,
    /// Post-rollback rejection window for unauthenticated initiations.
    pub rollback_cooldown_s: f64,
    /// Head start given to a released client's unilateral retry.
    pub collision_recovery_delay_s: f64,
    /// Redispatch delay when recovery is rate-limited.
    pub rate_limit_recovery_delay_s: f64,
}

impl Default for ManagerConfig {
    fn default() -> Self {
        Self {
            handshake_timeout_s: 10.0,
            responder_timeout_s: consts::NOISE_RESPONDER_HANDSHAKE_TIMEOUT_S,
            initiator_grace_s: consts::NOISE_RECENT_INITIATOR_GRACE_S,
            rollback_cooldown_s: consts::NOISE_RECONNECT_ROLLBACK_COOLDOWN_S,
            collision_recovery_delay_s: consts::NOISE_COLLISION_RECOVERY_DELAY_S,
            rate_limit_recovery_delay_s: consts::NOISE_RATE_LIMIT_RECOVERY_DELAY_S,
        }
    }
}

/// A prepared initiation awaiting its one-shot claim.
#[derive(Debug, Clone)]
pub struct HandshakeInitiation {
    pub payload: Vec<u8>,
    pub attempt_id: AttemptId,
}

#[derive(Debug, Clone)]
pub struct HandshakeRecoveryRequest {
    pub peer_id: PeerId,
    pub recovery_id: RecoveryId,
}

impl HandshakeRecoveryRequest {
    pub fn new(peer_id: PeerId, recovery_id: RecoveryId) -> Self {
        Self { peer_id, recovery_id }
    }
}

#[derive(Debug, Clone)]
pub enum RecoveryPreparation {
    /// A fresh ordinary initiation (payload + claim token).
    Ordinary(HandshakeInitiation),
    /// A live handshaking session already owns the peer; its timer was
    /// re-armed and no new initiation is minted.
    Transferred,
}

/// Why a quarantined session was restored.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RestoreReason {
    /// Replacement failed terminally; the restored generation is
    /// immediately valid for outbound.
    Terminal,
    /// Bounded convergence retry pending; outbound drains must wait.
    PendingConvergence,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum TimerKind {
    /// Ordinary initiator bound. `notify`: emit SessionFailed on expiry.
    InitiatorTimeout { peer: PeerId, epoch: Epoch, notify: bool },
    /// Responder window. The deadline keeps the quarantine rollback
    /// window when present; duplicates never extend it.
    ResponderTimeout {
        peer: PeerId,
        epoch: Epoch,
        deadline_ms: u64,
        notify: bool,
        created_by_yield: bool,
    },
    /// Deferred crossed-initiation recovery after initiator completion.
    SuppressedInitiationRecovery { peer: PeerId, established_epoch: Epoch },
    /// Transport callback for a recovery request.
    RecoveryCallback { peer: PeerId, callback: CallbackId },
    /// Redispatch of a rate-limited / failed recovery attempt.
    DelayedRecoveryRedispatch { peer: PeerId, recovery: RecoveryId },
}

#[derive(Debug, Clone)]
struct Timer {
    kind: TimerKind,
    fire_at_ms: u64,
}

/// A parked, receive-only established session (Swift:
/// `QuarantinedTransport`).
struct Quarantined {
    session: NoiseSession,
    generation: GenerationId,
    epoch: Epoch,
    rollback_deadline_ms: u64,
}

#[derive(Debug, Clone)]
pub struct HandshakeProcessingResult {
    pub response: Option<Vec<u8>>,
    pub established: bool,
    pub generation: Option<GenerationId>,
}

#[derive(Debug, Clone)]
pub enum ManagerEvent {
    /// Fired after the manager barrier section returns (Swift dispatches
    /// after release; here: returned from the same call that established).
    SessionEstablished {
        peer: PeerId,
        remote_static: [u8; 32],
        generation: GenerationId,
    },
    SessionRestored {
        peer: PeerId,
        generation: GenerationId,
        reason: RestoreReason,
    },
    SessionFailed { peer: PeerId, error: NoiseError },
    HandshakeRecoveryRequired { request: HandshakeRecoveryRequest },
}

pub struct SessionManager {
    local_static: StaticSecret,
    local_peer_id: PeerId,
    config: ManagerConfig,
    clock: Clock,

    sessions: HashMap<PeerId, NoiseSession>,
    session_epochs: HashMap<PeerId, Epoch>,
    generations: HashMap<PeerId, GenerationId>,

    quarantined: HashMap<PeerId, Quarantined>,
    rollback_cooldown_until: HashMap<PeerId, u64>,

    initiation_tokens: HashMap<PeerId, AttemptId>,
    recent_initiator_completions: HashMap<PeerId, u64>,
    initiator_retry_notifications: HashMap<PeerId, bool>,
    responders_created_by_yield: HashSet<PeerId>,
    pending_recovery_ids: HashMap<PeerId, RecoveryId>,
    recovery_callbacks_fired: HashSet<CallbackId>,

    timers: Vec<Timer>,
    /// Buffered events awaiting the caller (Swift: async callbacks).
    pending_events: Vec<ManagerEvent>,

    epoch_counter: u64,
    id_counter: u128,
}

impl SessionManager {
    pub fn new(
        local_static: StaticSecret,
        local_peer_id: PeerId,
        config: ManagerConfig,
        clock: Clock,
    ) -> Self {
        SessionManager {
            local_static,
            local_peer_id,
            config,
            clock,
            sessions: HashMap::new(),
            session_epochs: HashMap::new(),
            generations: HashMap::new(),
            quarantined: HashMap::new(),
            rollback_cooldown_until: HashMap::new(),
            initiation_tokens: HashMap::new(),
            recent_initiator_completions: HashMap::new(),
            initiator_retry_notifications: HashMap::new(),
            responders_created_by_yield: HashSet::new(),
            pending_recovery_ids: HashMap::new(),
            recovery_callbacks_fired: HashSet::new(),
            timers: Vec::new(),
            pending_events: Vec::new(),
            epoch_counter: 0,
            id_counter: 0,
        }
    }

    fn now_ms(&self) -> u64 {
        (self.clock)()
    }

    fn next_epoch(&mut self) -> Epoch {
        self.epoch_counter += 1;
        Epoch(self.epoch_counter)
    }

    fn next_id(&mut self) -> u128 {
        self.id_counter += 1;
        self.id_counter
    }

    fn new_session(&self, peer: &PeerId, role: NoiseRole) -> NoiseSession {
        NoiseSession::with_clock(
            peer.clone(),
            role,
            StaticSecret::from(self.local_static.to_bytes()),
            self.clock.clone(),
        )
    }

    // ---- active-table install/remove -------------------------------------

    fn install_session(
        &mut self,
        peer: &PeerId,
        session: NoiseSession,
    ) -> (Epoch, GenerationId) {
        let epoch = self.next_epoch();
        let generation = GenerationId(self.next_id());
        self.session_epochs.insert(peer.clone(), epoch);
        self.sessions.insert(peer.clone(), session);
        self.generations.insert(peer.clone(), generation);
        (epoch, generation)
    }

    /// Swift `removeSessionLocked` — full teardown for a peer.
    fn remove_session_locked(&mut self, peer: &PeerId) {
        if let Some(s) = self.sessions.remove(peer) {
            let mut s = s;
            s.reset();
        }
        if let Some(mut q) = self.quarantined.remove(peer) {
            q.session.reset();
        }
        self.session_epochs.remove(peer);
        self.generations.remove(peer);
        self.initiation_tokens.remove(peer);
        self.recent_initiator_completions.remove(peer);
        self.initiator_retry_notifications.remove(peer);
        self.responders_created_by_yield.remove(peer);
        self.pending_recovery_ids.remove(peer);
        self.rollback_cooldown_until.remove(peer);
        self.timers
            .retain(|t| !matches!(&t.kind, TimerKind::InitiatorTimeout { peer: p, .. } if p == peer)
                && !matches!(&t.kind, TimerKind::ResponderTimeout { peer: p, .. } if p == peer)
                && !matches!(&t.kind, TimerKind::SuppressedInitiationRecovery { peer: p, .. } if p == peer)
                && !matches!(&t.kind, TimerKind::RecoveryCallback { peer: p, .. } if p == peer)
                && !matches!(&t.kind, TimerKind::DelayedRecoveryRedispatch { peer: p, .. } if p == peer));
    }

    // ---- public session API ------------------------------------------------

    pub fn get_session(&self, peer: &PeerId) -> Option<&NoiseSession> {
        self.sessions.get(peer)
    }

    pub fn is_awaiting_responder_completion(&self, peer: &PeerId) -> bool {
        matches!(
            self.sessions.get(peer),
            Some(s) if s.role == NoiseRole::Responder && s.is_handshaking()
        )
    }

    pub fn session_generation(&self, peer: &PeerId) -> Option<GenerationId> {
        match self.sessions.get(peer) {
            Some(s) if s.is_established() => self.generations.get(peer).copied(),
            _ => None,
        }
    }

    pub fn has_receive_session(&self, peer: &PeerId) -> bool {
        if let Some(s) = self.sessions.get(peer) {
            if s.is_established() {
                return true;
            }
            if s.role == NoiseRole::Responder && s.is_handshaking() {
                return self
                    .quarantined
                    .get(peer)
                    .map(|q| q.session.is_established())
                    .unwrap_or(false);
            }
        }
        false
    }

    pub fn get_remote_static_key(&self, peer: &PeerId) -> Option<[u8; 32]> {
        self.sessions.get(peer).and_then(|s| s.get_remote_static_public_key())
    }

    pub fn sessions_needing_rekey(&self) -> Vec<(PeerId, bool)> {
        self.sessions
            .iter()
            .filter(|(_, s)| s.is_established() && s.needs_renegotiation())
            .map(|(p, _)| (p.clone(), true))
            .collect()
    }

    pub fn remove_session(&mut self, peer: &PeerId) {
        self.remove_session_locked(peer);
    }

    pub fn remove_all_sessions(&mut self) {
        let peers: Vec<PeerId> = self.sessions.keys().cloned().collect();
        for p in peers {
            self.remove_session_locked(&p);
        }
        let quarantined_peers: Vec<PeerId> = self.quarantined.keys().cloned().collect();
        for p in quarantined_peers {
            self.remove_session_locked(&p);
        }
        self.timers.clear();
    }

    // ---- initiation ---------------------------------------------------------

    /// Swift `initiateHandshake` — unconditional initiator; errors if an
    /// established session exists; replaces a non-established one.
    pub fn initiate_handshake(&mut self, peer: &PeerId) -> Result<Vec<u8>, NoiseError> {
        if let Some(s) = self.sessions.get(peer) {
            if s.is_established() {
                return Err(NoiseError::AlreadyEstablished);
            }
        }
        self.remove_session_locked(peer);
        self.recent_initiator_completions.remove(peer);
        let mut session = self.new_session(peer, NoiseRole::Initiator);
        let payload = session.start_handshake()?;
        let (epoch, _gen) = self.install_session(peer, session);
        self.arm_initiator_timeout(peer, epoch, false);
        Ok(payload)
    }

    /// Swift `initiateHandshakeIfAbsent` — only when nothing owns the peer.
    pub fn initiate_handshake_if_absent<F>(
        &mut self,
        peer: &PeerId,
        notify_on_timeout: bool,
        authorize: F,
    ) -> Result<Option<HandshakeInitiation>, NoiseError>
    where
        F: FnOnce() -> Result<(), NoiseError>,
    {
        if self.sessions.contains_key(peer) || self.quarantined.contains_key(peer) {
            return Ok(None);
        }
        authorize()?;
        self.recent_initiator_completions.remove(peer);
        self.rollback_cooldown_until.remove(peer);
        let mut session = self.new_session(peer, NoiseRole::Initiator);
        let payload = session.start_handshake()?;
        let attempt = AttemptId(self.next_id());
        let (epoch, _gen) = self.install_session(peer, session);
        self.initiation_tokens.insert(peer.clone(), attempt);
        self.arm_initiator_timeout(peer, epoch, notify_on_timeout);
        Ok(Some(HandshakeInitiation { payload, attempt_id: attempt }))
    }

    /// Swift `initiateReconnectHandshake` — rekey/restart: prepare first,
    /// then retire the established session atomically.
    pub fn initiate_reconnect_handshake<F>(
        &mut self,
        peer: &PeerId,
        notify_on_timeout: bool,
        authorize: F,
    ) -> Result<HandshakeInitiation, NoiseError>
    where
        F: FnOnce() -> Result<(), NoiseError>,
    {
        if !self
            .sessions
            .get(peer)
            .map(|s| s.is_established())
            .unwrap_or(false)
        {
            return Err(NoiseError::NotEstablished);
        }
        authorize()?;
        // Prepare before retiring: on failure the working session survives.
        let mut next = self.new_session(peer, NoiseRole::Initiator);
        let payload = next.start_handshake()?;
        // Retire the established session.
        self.remove_session_locked(peer);
        let (epoch, _gen) = self.install_session(peer, next);
        let attempt = AttemptId(self.next_id());
        self.initiation_tokens.insert(peer.clone(), attempt);
        self.arm_initiator_timeout(peer, epoch, notify_on_timeout);
        Ok(HandshakeInitiation { payload, attempt_id: attempt })
    }

    pub fn initiate_rekey(&mut self, peer: &PeerId) -> Result<HandshakeInitiation, NoiseError> {
        self.initiate_reconnect_handshake(peer, true, || Ok(()))
    }

    /// Swift `claimHandshakeInitiation` — exactly-once claim of a prepared
    /// initiation; re-arms the full initiator window.
    pub fn claim_handshake_initiation(
        &mut self,
        initiation: &HandshakeInitiation,
        peer: &PeerId,
    ) -> Option<Vec<u8>> {
        if self.initiation_tokens.get(peer) != Some(&initiation.attempt_id) {
            return None;
        }
        let current_is_initiator_handshaking = matches!(
            self.sessions.get(peer),
            Some(s) if s.role == NoiseRole::Initiator && s.is_handshaking()
        );
        if !current_is_initiator_handshaking {
            return None;
        }
        self.initiation_tokens.remove(peer);
        // Re-arm the full window: the peer gets the whole bound once the
        // exact bytes are on the wire.
        if let Some(epoch) = self.session_epochs.get(peer).copied() {
            self.arm_initiator_timeout(peer, epoch, true);
        }
        Some(initiation.payload.clone())
    }


    // ---- ingress state machine ---------------------------------------------

    /// Swift `handleIncomingHandshakeWithResult` — the core ingress path
    /// with the four fresh-initiation gates in load-bearing order:
    /// cooldown, coalesce, grace, crossed-initiation.
    pub fn handle_incoming_with_result(
        &mut self,
        peer: &PeerId,
        message: &[u8],
    ) -> Result<HandshakeProcessingResult, NoiseError> {
        let now = self.now_ms();
        let existing_established = self
            .sessions
            .get(peer)
            .map(|s| s.is_established())
            .unwrap_or(false);

        // Fresh-initiation classifier (Swift MGR:546-553).
        let is_fresh_initiation = message.len() == consts::NOISE_XX_INITIAL_MESSAGE_SIZE
            || self.sessions.get(peer).is_none()
            || (existing_established && message.len() > consts::NOISE_XX_INITIAL_MESSAGE_SIZE);

        if is_fresh_initiation {
            // Gate 1 — rollback cooldown (Swift MGR:556-566).
            if let Some(&until) = self.rollback_cooldown_until.get(peer) {
                if now < until && existing_established {
                    return Ok(HandshakeProcessingResult {
                        response: None,
                        established: false,
                        generation: None,
                    });
                }
                self.rollback_cooldown_until.remove(peer);
            }

            // Gate 2 — coalesce duplicates while convergence recovery is
            // pending (Swift MGR:568-576).
            if self.pending_recovery_ids.contains_key(peer)
                && self.local_wins(peer)
                && existing_established
            {
                return Ok(HandshakeProcessingResult {
                    response: None,
                    established: false,
                    generation: None,
                });
            }

            // Gate 3 — recent-initiator-completion grace (Swift MGR:578-598).
            if let Some(&completed_at) = self.recent_initiator_completions.get(peer) {
                let in_grace = (now - completed_at) as f64 / 1000.0 < self.config.initiator_grace_s;
                if in_grace && self.local_wins(peer) && existing_established {
                    self.recent_initiator_completions.remove(peer);
                    if let Some(epoch) = self.session_epochs.get(peer).copied() {
                        self.timers.push(Timer {
                            kind: TimerKind::SuppressedInitiationRecovery {
                                peer: peer.clone(),
                                established_epoch: epoch,
                            },
                            fire_at_ms: now + (self.config.initiator_grace_s * 1000.0) as u64,
                        });
                    }
                    return Ok(HandshakeProcessingResult {
                        response: None,
                        established: false,
                        generation: None,
                    });
                }
            }

            // Gate 4 — simultaneous initiation (Swift MGR:600-619).
            let crossed_initiator = matches!(
                self.sessions.get(peer),
                Some(s) if s.role == NoiseRole::Initiator && s.is_handshaking()
            );
            let mut did_yield = false;
            let mut inherited_retry_notify = false;
            if crossed_initiator {
                if self.local_wins(peer) {
                    // Deterministic initiator role: keep ours, drop theirs.
                    return Ok(HandshakeProcessingResult {
                        response: None,
                        established: false,
                        generation: None,
                    });
                }
                // We lose: yield our initiator, become the responder.
                inherited_retry_notify = self
                    .initiator_retry_notifications
                    .remove(peer)
                    .unwrap_or(false);
                did_yield = true;
                self.drop_current_session(peer);
            }

            // Quarantine an established session on unauthenticated fresh
            // initiation (Swift MGR:637-657).
            if let Some(mut existing) = self.sessions.remove(peer) {
                if existing.is_established() {
                    let generation = self.generations.remove(peer).unwrap_or(GenerationId(0));
                    let epoch = self.session_epochs.remove(peer).unwrap_or(Epoch(0));
                    self.initiation_tokens.remove(peer);
                    self.cancel_peer_timers(peer);
                    let deadline = now + (self.config.responder_timeout_s * 1000.0) as u64;
                    let prior = self.quarantined.insert(
                        peer.clone(),
                        Quarantined {
                            session: existing,
                            generation,
                            epoch,
                            rollback_deadline_ms: deadline,
                        },
                    );
                    if let Some(mut p) = prior {
                        p.session.reset();
                    }
                } else {
                    // Half-open leftover: drop it entirely.
                    existing.reset();
                    self.drop_current_session(peer);
                }
            }

            // Create the responder for this initiation (Swift MGR:683-703).
            let mut session = self.new_session(peer, NoiseRole::Responder);
            match session.process_handshake_message(message) {
                Ok(Some(response)) => {
                    let (epoch, _gen) = self.install_session(peer, session);
                    if did_yield {
                        self.responders_created_by_yield.insert(peer.clone());
                    }
                    // Deadline keeper: keep the quarantine rollback window
                    // when present; duplicates never extend it.
                    let deadline = self
                        .quarantined
                        .get(peer)
                        .map(|q| q.rollback_deadline_ms)
                        .unwrap_or_else(|| now + (self.config.responder_timeout_s * 1000.0) as u64);
                    self.arm_responder_timeout(
                        peer,
                        epoch,
                        deadline,
                        inherited_retry_notify,
                        did_yield,
                    );
                    return Ok(HandshakeProcessingResult {
                        response: Some(response),
                        established: false,
                        generation: None,
                    });
                }
                Ok(None) => {
                    // Message 1 never completes a responder handshake.
                    session.reset();
                    return Err(NoiseError::InvalidState);
                }
                Err(e) => {
                    session.reset();
                    return Err(e);
                }
            }
        }

        // Continuation of an existing session (message 2 or 3).
        let Some(session) = self.sessions.get_mut(peer) else {
            return Err(NoiseError::SessionNotFound);
        };
        let outcome = session.process_handshake_message(message);
        let session_established = session.is_established();
        let session_role = session.role;
        let remote = session.get_remote_static_public_key();
        match outcome {
            Ok(resp) => {
                if !session_established {
                    return Ok(HandshakeProcessingResult {
                        response: resp,
                        established: false,
                        generation: None,
                    });
                }
                // Establishment: fail closed on the claimed identity.
                let Some(remote) = remote else {
                    let e = NoiseError::PeerIdentityMismatch;
                    return Err(self.fail_current_session(peer, now, e));
                };
                if !authenticated_remote_key(&remote, peer) {
                    let e = NoiseError::PeerIdentityMismatch;
                    return Err(self.fail_current_session(peer, now, e));
                }
                let generation = self.generations.get(peer).copied().unwrap_or(GenerationId(0));
                // Clear quarantine + cooldown + token + timers.
                if let Some(mut q) = self.quarantined.remove(peer) {
                    q.session.reset();
                }
                self.rollback_cooldown_until.remove(peer);
                self.initiation_tokens.remove(peer);
                self.cancel_peer_timers(peer);
                if session_role == NoiseRole::Initiator {
                    self.recent_initiator_completions.insert(peer.clone(), now);
                }
                self.pending_events.push(ManagerEvent::SessionEstablished {
                    peer: peer.clone(),
                    remote_static: remote,
                    generation,
                });
                Ok(HandshakeProcessingResult {
                    response: resp,
                    established: true,
                    generation: Some(generation),
                })
            }
            Err(e) => Err(self.fail_current_session(peer, now, e)),
        }
    }

    /// Swift `handleIncomingHandshake` — response bytes only.
    pub fn handle_incoming(&mut self, peer: &PeerId, message: &[u8]) -> Result<Option<Vec<u8>>, NoiseError> {
        self.handle_incoming_with_result(peer, message)
            .map(|r| r.response)
    }

    /// Deterministic crossed-initiation tiebreak: the lexicographically
    /// smaller short ID keeps the initiator role.
    fn local_wins(&self, peer: &PeerId) -> bool {
        self.local_peer_id.short() < peer.short()
    }

    /// Remove the peer's current session entry entirely (any state).
    fn drop_current_session(&mut self, peer: &PeerId) {
        if let Some(mut s) = self.sessions.remove(peer) {
            s.reset();
        }
        self.session_epochs.remove(peer);
        self.generations.remove(peer);
        self.initiation_tokens.remove(peer);
        self.cancel_peer_timers(peer);
    }

    /// Handshake-processing failure path (Swift MGR:745-828): clean up the
    /// failed session, restore the quarantine if present, mark the cooldown,
    /// buffer the restore/failed events, and return the error the caller
    /// should see — `ManagedHandshakeFailure` when the manager owns
    /// recovery, so the caller does not spin its own retries.
    fn fail_current_session(&mut self, peer: &PeerId, now: u64, underlying: NoiseError) -> NoiseError {
        let is_identity_mismatch = underlying == NoiseError::PeerIdentityMismatch;
        self.drop_current_session(peer);
        let owns_recovery = self.responders_created_by_yield.remove(peer)
            || self.pending_recovery_ids.contains_key(peer);
        let mut restored: Option<(GenerationId, RestoreReason)> = None;
        if let Some(q) = self.quarantined.remove(peer) {
            let gen = q.generation;
            let epoch = q.epoch;
            let q_session = q.session;
            // The quarantined session goes back in as the active transport.
            self.session_epochs.insert(peer.clone(), epoch);
            self.sessions.insert(peer.clone(), q_session);
            self.generations.insert(peer.clone(), gen);
            self.mark_rollback_cooldown(peer, now);
            let reason = if !is_identity_mismatch && owns_recovery {
                RestoreReason::PendingConvergence
            } else {
                RestoreReason::Terminal
            };
            restored = Some((gen, reason));
        }
        if let Some((gen, reason)) = restored {
            self.pending_events.push(ManagerEvent::SessionRestored {
                peer: peer.clone(),
                generation: gen,
                reason,
            });
        }
        self.pending_events.push(ManagerEvent::SessionFailed {
            peer: peer.clone(),
            error: underlying.clone(),
        });
        if owns_recovery {
            NoiseError::ManagedHandshakeFailure(Box::new(underlying))
        } else {
            underlying
        }
    }

    fn mark_rollback_cooldown(&mut self, peer: &PeerId, now: u64) {
        self.rollback_cooldown_until
            .insert(peer.clone(), now + (self.config.rollback_cooldown_s * 1000.0) as u64);
    }

    // ---- transport -----------------------------------------------------------

    pub fn encrypt(&mut self, plaintext: &[u8], peer: &PeerId) -> Result<Vec<u8>, NoiseError> {
        let Some(session) = self.sessions.get_mut(peer) else {
            return Err(NoiseError::SessionNotFound);
        };
        session.encrypt(plaintext)
    }

    /// Generation-pinned encrypt — a rekey between capability proof and
    /// media encryption fails closed (Swift MGR:1158-1171).
    pub fn encrypt_with_generation(
        &mut self,
        plaintext: &[u8],
        peer: &PeerId,
        expected: GenerationId,
    ) -> Result<Vec<u8>, NoiseError> {
        let Some(session) = self.sessions.get_mut(peer) else {
            return Err(NoiseError::SessionNotFound);
        };
        if !session.is_established() || self.generations.get(peer) != Some(&expected) {
            return Err(NoiseError::NotEstablished);
        }
        session.encrypt(plaintext)
    }

    pub fn decrypt(&mut self, ciphertext: &[u8], peer: &PeerId) -> Result<Vec<u8>, NoiseError> {
        self.decrypt_with_session_generation(ciphertext, peer, &|_| true, || Ok(()))
            .map(|(pt, _)| pt)
    }

    /// Swift `decryptWithSessionGeneration` — priority order:
    /// (1) established session + generation; (2) handshaking responder +
    /// quarantined session (receive-only rollback window); (3) absent.
    pub fn decrypt_with_session_generation<A, F>(
        &mut self,
        ciphertext: &[u8],
        peer: &PeerId,
        established_generation_is_ready: A,
        authorize_decrypt: F,
    ) -> Result<(Vec<u8>, GenerationId), NoiseError>
    where
        A: Fn(GenerationId) -> bool,
        F: FnOnce() -> Result<(), NoiseError>,
    {
        enum Active<'a> {
            Established(&'a mut NoiseSession, GenerationId),
            Quarantined(&'a mut NoiseSession, GenerationId),
        }
        let active = {
            let Some(session) = self.sessions.get_mut(peer) else {
                return Err(NoiseError::SessionNotFound);
            };
            if session.is_established() {
                let gen = self.generations.get(peer).copied().unwrap_or(GenerationId(0));
                if !established_generation_is_ready(gen) {
                    return Err(NoiseError::TransportGenerationNotReady);
                }
                Active::Established(session, gen)
            } else if session.role == NoiseRole::Responder && session.is_handshaking() {
                let Some(q) = self.quarantined.get_mut(peer) else {
                    return Err(NoiseError::NotEstablished);
                };
                Active::Quarantined(&mut q.session, q.generation)
            } else {
                return Err(NoiseError::NotEstablished);
            }
        };
        authorize_decrypt()?;
        // Quarantine is receive-only: old keys cannot encrypt or advertise
        // an established generation, but legitimate in-flight ciphertext
        // from the retained peer may still advance and later resume.
        match active {
            Active::Established(session, gen) => {
                let pt = session.decrypt(ciphertext)?;
                Ok((pt, gen))
            }
            Active::Quarantined(q_session, gen) => {
                let pt = q_session.decrypt(ciphertext)?;
                Ok((pt, gen))
            }
        }
    }

    // ---- recovery ---------------------------------------------------------------

    /// Swift `prepareHandshakeRecovery` — transfer to a live handshaking
    /// session, or mint exactly one manager-owned retry.
    pub fn prepare_handshake_recovery<F>(
        &mut self,
        request: &HandshakeRecoveryRequest,
        authorize_attempt: F,
    ) -> Result<Option<RecoveryPreparation>, NoiseError>
    where
        F: FnOnce() -> Result<(), NoiseError>,
    {
        let peer = &request.peer_id;
        if self.pending_recovery_ids.get(peer) != Some(&request.recovery_id) {
            return Ok(None); // stale
        }
        let now = self.now_ms();

        // A live handshaking session already owns the peer: re-arm only.
        if let Some(session) = self.sessions.get(peer) {
            if session.is_handshaking() {
                if let Some(epoch) = self.session_epochs.get(peer).copied() {
                    let deadline = self
                        .quarantined
                        .get(peer)
                        .map(|q| q.rollback_deadline_ms)
                        .unwrap_or_else(|| now + (self.config.responder_timeout_s * 1000.0) as u64);
                    let notify = session.role == NoiseRole::Initiator;
                    let by_yield = session.role == NoiseRole::Responder;
                    self.cancel_peer_timers(peer);
                    self.arm_responder_or_initiator(peer, epoch, deadline, notify, by_yield);
                }
                return Ok(Some(RecoveryPreparation::Transferred));
            }
        }

        // Authorize; a denied attempt stays actionable via redispatch.
        if let Err(e) = authorize_attempt() {
            self.schedule_recovery_redispatch(peer, request.recovery_id, now, true);
            return Err(e);
        }

        // Mint the one retry owned by this request.
        let mut next = self.new_session(peer, NoiseRole::Initiator);
        let payload = match next.start_handshake() {
            Ok(p) => p,
            Err(e) => {
                next.reset();
                self.schedule_recovery_redispatch(peer, request.recovery_id, now, false);
                return Err(e);
            }
        };
        // Retire the current session AND any quarantine atomically.
        self.drop_current_session(peer);
        if let Some(mut q) = self.quarantined.remove(peer) {
            q.session.reset();
        }
        self.rollback_cooldown_until.remove(peer);
        let (epoch, _gen) = self.install_session(peer, next);
        let attempt = AttemptId(self.next_id());
        self.initiation_tokens.insert(peer.clone(), attempt);
        // This is the one retry owned by the request: it may time out but
        // must not recursively mint another generation.
        self.arm_initiator_timeout(peer, epoch, false);
        self.consume_recovery_locked(request);
        Ok(Some(RecoveryPreparation::Ordinary(HandshakeInitiation {
            payload,
            attempt_id: attempt,
        })))
    }

    pub fn cancel_handshake_recovery(&mut self, request: &HandshakeRecoveryRequest) {
        if self.pending_recovery_ids.get(&request.peer_id) == Some(&request.recovery_id) {
            self.pending_recovery_ids.remove(&request.peer_id);
            self.timers.retain(|t| {
                !matches!(&t.kind, TimerKind::DelayedRecoveryRedispatch { recovery, .. } if *recovery == request.recovery_id)
            });
        }
    }

    fn consume_recovery_locked(&mut self, request: &HandshakeRecoveryRequest) {
        self.pending_recovery_ids.remove(&request.peer_id);
        self.timers.retain(|t| {
            !matches!(&t.kind, TimerKind::DelayedRecoveryRedispatch { recovery, .. } if *recovery == request.recovery_id)
        });
    }

    fn schedule_recovery_redispatch(
        &mut self,
        peer: &PeerId,
        recovery: RecoveryId,
        now: u64,
        rate_limited: bool,
    ) {
        let delay = if rate_limited {
            self.config.rate_limit_recovery_delay_s
        } else {
            self.config.collision_recovery_delay_s
        };
        self.timers.push(Timer {
            kind: TimerKind::DelayedRecoveryRedispatch {
                peer: peer.clone(),
                recovery,
            },
            fire_at_ms: now + (delay * 1000.0) as u64,
        });
    }

    fn arm_responder_or_initiator(
        &mut self,
        peer: &PeerId,
        epoch: Epoch,
        deadline_ms: u64,
        notify: bool,
        by_yield: bool,
    ) {
        if self
            .sessions
            .get(peer)
            .map(|s| s.role == NoiseRole::Initiator)
            .unwrap_or(false)
        {
            self.arm_initiator_timeout(peer, epoch, notify);
        } else {
            self.arm_responder_timeout(peer, epoch, deadline_ms, notify, by_yield);
        }
    }

    // ---- timers -----------------------------------------------------------------

    fn arm_initiator_timeout(&mut self, peer: &PeerId, epoch: Epoch, notify: bool) {
        let now = self.now_ms();
        self.timers.retain(|t| {
            !matches!(&t.kind, TimerKind::InitiatorTimeout { peer: p, .. } if p == peer)
        });
        self.timers.push(Timer {
            kind: TimerKind::InitiatorTimeout {
                peer: peer.clone(),
                epoch,
                notify,
            },
            fire_at_ms: now + (self.config.handshake_timeout_s * 1000.0) as u64,
        });
        self.initiator_retry_notifications.insert(peer.clone(), notify);
    }

    fn arm_responder_timeout(
        &mut self,
        peer: &PeerId,
        epoch: Epoch,
        deadline_ms: u64,
        notify: bool,
        created_by_yield: bool,
    ) {
        self.timers.retain(|t| {
            !matches!(&t.kind, TimerKind::ResponderTimeout { peer: p, .. } if p == peer)
        });
        self.timers.push(Timer {
            kind: TimerKind::ResponderTimeout {
                peer: peer.clone(),
                epoch,
                deadline_ms,
                notify,
                created_by_yield,
            },
            fire_at_ms: deadline_ms,
        });
    }

    fn cancel_peer_timers(&mut self, peer: &PeerId) {
        self.timers.retain(|t| match &t.kind {
            TimerKind::InitiatorTimeout { peer: p, .. }
            | TimerKind::ResponderTimeout { peer: p, .. }
            | TimerKind::SuppressedInitiationRecovery { peer: p, .. }
            | TimerKind::RecoveryCallback { peer: p, .. }
            | TimerKind::DelayedRecoveryRedispatch { peer: p, .. } => p != peer,
        });
    }

    /// Next deadline for the engine's tick scheduler (None = idle).
    pub fn next_deadline_ms(&self) -> Option<u64> {
        self.timers.iter().map(|t| t.fire_at_ms).min()
    }

    /// Process due timers; returns nothing — events land in
    /// [`SessionManager::take_events`].
    pub fn handle_deadlines(&mut self, now: u64) {
        let due: Vec<Timer> = self
            .timers
            .iter()
            .filter(|t| t.fire_at_ms <= now)
            .cloned()
            .collect();
        if due.is_empty() {
            return;
        }
        self.timers.retain(|t| t.fire_at_ms > now);
        for timer in due {
            match timer.kind {
                TimerKind::InitiatorTimeout { peer, epoch, notify } => {
                    let current_epoch = self.session_epochs.get(&peer).copied();
                    let is_live_initiator = matches!(
                        self.sessions.get(&peer),
                        Some(s) if s.role == NoiseRole::Initiator && s.is_handshaking()
                    );
                    if current_epoch != Some(epoch) || !is_live_initiator {
                        continue; // stale
                    }
                    self.drop_current_session(&peer);
                    if notify {
                        self.pending_events.push(ManagerEvent::SessionFailed {
                            peer: peer.clone(),
                            error: NoiseError::HandshakeNotComplete,
                        });
                    }
                }
                TimerKind::ResponderTimeout { peer, epoch, deadline_ms, notify, created_by_yield: _ } => {
                    let current_epoch = self.session_epochs.get(&peer).copied();
                    let is_live_responder = matches!(
                        self.sessions.get(&peer),
                        Some(s) if s.role == NoiseRole::Responder && s.is_handshaking()
                    );
                    if current_epoch != Some(epoch) || !is_live_responder {
                        continue; // stale
                    }
                    self.drop_current_session(&peer);
                    // Restore the quarantined transport (Swift MGR:924-943).
                    let mut restored: Option<(GenerationId, RestoreReason)> = None;
                    if let Some(q) = self.quarantined.remove(&peer) {
                        let gen = q.generation;
                        let epoch = q.epoch;
                        let mut q_session = q.session;
                        self.session_epochs.insert(peer.clone(), epoch);
                        self.sessions.insert(peer.clone(), q_session);
                                    self.generations.insert(peer.clone(), gen);
                        self.mark_rollback_cooldown(&peer, now);
                        restored = Some((gen, RestoreReason::PendingConvergence));
                    }
                    if let Some((gen, reason)) = restored {
                        self.pending_events.push(ManagerEvent::SessionRestored {
                            peer: peer.clone(),
                            generation: gen,
                            reason,
                        });
                    }
                    if notify {
                        self.pending_events.push(ManagerEvent::SessionFailed {
                            peer: peer.clone(),
                            error: NoiseError::HandshakeNotComplete,
                        });
                    }
                    // A rollback always owns one local convergence attempt.
                    let recovery = RecoveryId(self.next_id());
                    self.pending_recovery_ids.insert(peer.clone(), recovery);
                    self.pending_events.push(ManagerEvent::HandshakeRecoveryRequired {
                        request: HandshakeRecoveryRequest::new(peer.clone(), recovery),
                    });
                }
                TimerKind::SuppressedInitiationRecovery { peer, established_epoch } => {
                    let still = self.session_epochs.get(&peer).copied() == Some(established_epoch)
                        && self.sessions.get(&peer).map(|s| s.is_established()).unwrap_or(false);
                    if !still {
                        continue;
                    }
                    let recovery = RecoveryId(self.next_id());
                    self.pending_recovery_ids.insert(peer.clone(), recovery);
                    self.pending_events.push(ManagerEvent::HandshakeRecoveryRequired {
                        request: HandshakeRecoveryRequest::new(peer.clone(), recovery),
                    });
                }
                TimerKind::RecoveryCallback { peer, callback } => {
                    if self.recovery_callbacks_fired.contains(&callback) {
                        continue;
                    }
                    self.recovery_callbacks_fired.insert(callback);
                    let recovery = RecoveryId(self.next_id());
                    self.pending_recovery_ids.insert(peer.clone(), recovery);
                    self.pending_events.push(ManagerEvent::HandshakeRecoveryRequired {
                        request: HandshakeRecoveryRequest::new(peer.clone(), recovery),
                    });
                }
                TimerKind::DelayedRecoveryRedispatch { peer, recovery } => {
                    // The manager retries its own recovery after the head
                    // start; only if still pending and nothing else owns it.
                    if self.pending_recovery_ids.get(&peer) != Some(&recovery) {
                        continue;
                    }
                    if self.sessions.get(&peer).map(|s| s.is_handshaking()).unwrap_or(false) {
                        continue;
                    }
                    self.pending_events.push(ManagerEvent::HandshakeRecoveryRequired {
                        request: HandshakeRecoveryRequest::new(peer.clone(), recovery),
                    });
                }
            }
        }
    }

    /// Drain buffered events (Swift: async callbacks).
    pub fn take_events(&mut self) -> Vec<ManagerEvent> {
        std::mem::take(&mut self.pending_events)
    }
}

/// Swift `authenticatedRemoteKey` (MGR:1130-1142) — fail-closed check of
/// the authenticated remote static against the claimed peer identity.
fn authenticated_remote_key(remote: &[u8; 32], claimed: &PeerId) -> bool {
    if claimed.is_short() {
        return PeerId::from_public_key(remote) == *claimed;
    }
    if let Some(noise_key) = claimed.noise_key() {
        return noise_key == *remote;
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::noise::handshake::public_bytes;
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::sync::Arc;
    use x25519_dalek::StaticSecret;

    /// Deterministic clock shared by manager and its sessions.
    fn shared_clock(t: u64) -> (Arc<AtomicU64>, Clock) {
        let cell = Arc::new(AtomicU64::new(t));
        let cell2 = cell.clone();
        let clock: Clock = Arc::new(move || cell2.load(Ordering::SeqCst));
        (cell, clock)
    }

    fn manager(
        secret: StaticSecret,
        local_id_hex: &str,
        t: u64,
    ) -> (SessionManager, Arc<AtomicU64>) {
        let (cell, clock) = shared_clock(t);
        let local_id = PeerId::new(local_id_hex);
        let m = SessionManager::new(secret, local_id, ManagerConfig::default(), clock);
        (m, cell)
    }

    fn secret_from(seed: u8) -> StaticSecret {
        StaticSecret::from([seed; 32])
    }

    #[test]
    fn generation_flow_and_pinned_crypto() {
        // A < B lexicographically? Pick ids so A is the initiator.
        let a_key = secret_from(0xA1);
        let b_key = secret_from(0xB2);
        let a_id = PeerId::from_public_key(&public_bytes(&a_key));
        let b_id = PeerId::from_public_key(&public_bytes(&b_key));
        let (mut a, _) = manager(a_key, &a_id.to_string(), 1_000);
        let (mut b, _) = manager(b_key, &b_id.to_string(), 1_000);

        let m1 = a.initiate_handshake(&b_id).unwrap();
        let out = b.handle_incoming_with_result(&a_id, &m1).unwrap();
        let m2 = out.response.unwrap();
        let out23 = a.handle_incoming_with_result(&b_id, &m2).unwrap();
        assert!(out23.established);
        let m3 = out23.response.unwrap();
        let out3 = b.handle_incoming_with_result(&a_id, &m3).unwrap();
        assert!(out3.established);

        // Both sides have generations and pinned crypto works.
        let gen_a = a.session_generation(&b_id).expect("gen a");
        let gen_b = b.session_generation(&a_id).expect("gen b");
        let ct = a
            .encrypt_with_generation(b"ping", &b_id, gen_a)
            .expect("pinned encrypt");
        let (pt, gen_used) = b
            .decrypt_with_session_generation(&ct, &a_id, &|_| true, || Ok(()))
            .expect("pinned decrypt");
        assert_eq!(pt, b"ping");
        assert_eq!(gen_used, gen_b);

        // A stale generation fails closed.
        let stale = GenerationId(999_999);
        assert_eq!(
            a.encrypt_with_generation(b"x", &b_id, stale),
            Err(NoiseError::NotEstablished)
        );
    }

    #[test]
    fn quarantine_on_fresh_initiation_and_rollback_restore() {
        let a_key = secret_from(0xA1);
        let b_key = secret_from(0xB2);
        let a_id = PeerId::from_public_key(&public_bytes(&a_key));
        let b_id = PeerId::from_public_key(&public_bytes(&b_key));
        let (mut a, a_clock) = manager(a_key, &a_id.to_string(), 1_000);
        let (mut b, b_clock) = manager(b_key, &b_id.to_string(), 1_000);

        // Establish an A↔B session the normal way.
        let m1 = a.initiate_handshake(&b_id).unwrap();
        let m2 = b.handle_incoming_with_result(&a_id, &m1).unwrap().response.unwrap();
        let out23 = a.handle_incoming_with_result(&b_id, &m2).unwrap();
        let m3 = out23.response.unwrap();
        b.handle_incoming_with_result(&a_id, &m3).unwrap();
        let orig_gen = b.session_generation(&a_id).expect("gen");

        // In-flight ciphertext under the original keys (sent before the
        // attack traffic, arrives during quarantine).
        let inflight = a.encrypt(b"in-flight", &b_id).unwrap();

        // Unauthenticated fresh initiation quarantines the session.
        let attacker = secret_from(0xEE);
        let attacker_id = PeerId::from_public_key(&public_bytes(&attacker));
        let _ = attacker_id;
        let (mut attacker_mgr, _) = manager(attacker, "deadbeef0000000f", 1_000);
        let fake_m1 = attacker_mgr.initiate_handshake(&b_id).unwrap();
        let out = b.handle_incoming_with_result(&a_id, &fake_m1).unwrap();
        let m2_att = out.response.expect("responder m2");

        // B is now a handshaking responder; the old session is quarantined
        // but still receives.
        assert!(b.has_receive_session(&a_id));
        let (pt, gen) = b
            .decrypt_with_session_generation(&inflight, &a_id, &|_| true, || Ok(()))
            .expect("quarantined decrypt");
        assert_eq!(pt, b"in-flight");
        assert_eq!(gen, orig_gen);
        // Outbound under quarantine fails: old keys cannot encrypt.
        assert_eq!(b.encrypt(b"x", &a_id), Err(NoiseError::NotEstablished));

        // Attacker never completes; responder window (20 s) expires.
        b_clock.store(1_000 + 20_500, Ordering::SeqCst);
        a_clock.store(1_000 + 20_500, Ordering::SeqCst);
        b.handle_deadlines(1_000 + 20_500);
        let events = b.take_events();
        assert!(
            events.iter().any(|e| matches!(
                e,
                ManagerEvent::SessionRestored {
                    generation, ..
                } if *generation == orig_gen
            )),
            "expected rollback restore, got {events:?}"
        );

        // Restored session works again.
        assert!(b.session_generation(&a_id).is_some());
        let restored_gen = b.session_generation(&a_id).unwrap();
        assert_eq!(restored_gen, orig_gen);
        let ct = b.encrypt(b"after-restore", &a_id).unwrap();
        let pt = a.decrypt(&ct, &b_id).unwrap();
        assert_eq!(pt, b"after-restore");

        // The 60 s cooldown drops further unauthenticated initiations while
        // the restored session is established.
        let again = attacker_mgr.initiate_handshake(&b_id).unwrap();
        let dropped = b.handle_incoming_with_result(&a_id, &again).unwrap();
        assert!(dropped.response.is_none());
        assert!(!dropped.established);
        assert!(b.session_generation(&a_id).is_some());
        let _ = m2_att;
    }

    #[test]
    fn crossed_initiation_resolved_by_lexicographic_order() {
        // Both sides initiate simultaneously. The lexicographically smaller
        // short ID keeps the initiator role; the other yields.
        let a_key = secret_from(0x01);
        let b_key = secret_from(0x02);
        let a_id = PeerId::from_public_key(&public_bytes(&a_key));
        let b_id = PeerId::from_public_key(&public_bytes(&b_key));
        // Whoever is smaller wins; compute it.
        let a_wins = a_id.short().as_str() < b_id.short().as_str();

        let (mut a, _) = manager(a_key, &a_id.to_string(), 5_000);
        let (mut b, _) = manager(b_key, &b_id.to_string(), 5_000);
        let m1a = a.initiate_handshake(&b_id).unwrap();
        let m1b = b.initiate_handshake(&a_id).unwrap();

        // Each side receives the other's message 1 while its own initiator
        // is outstanding.
        let out_a = a.handle_incoming_with_result(&b_id, &m1b).unwrap();
        let out_b = b.handle_incoming_with_result(&a_id, &m1a).unwrap();
        if a_wins {
            // A keeps its initiator; B yields and answers.
            assert!(out_a.response.is_none());
            let m2 = out_b.response.expect("B answers as responder");
            let out23 = a.handle_incoming_with_result(&b_id, &m2).unwrap();
            assert!(out23.established);
            let m3 = out23.response.unwrap();
            let out3 = b.handle_incoming_with_result(&a_id, &m3).unwrap();
            assert!(out3.established);
        } else {
            assert!(out_b.response.is_none());
            let m2 = out_a.response.expect("A answers as responder");
            let out23 = b.handle_incoming_with_result(&a_id, &m2).unwrap();
            assert!(out23.established);
            let m3 = out23.response.unwrap();
            let out3 = a.handle_incoming_with_result(&b_id, &m3).unwrap();
            assert!(out3.established);
        }
        assert!(a.get_session(&b_id).unwrap().is_established());
        assert!(b.get_session(&a_id).unwrap().is_established());
    }

    #[test]
    fn grace_period_defers_crossed_initiation_after_completion() {
        // The tiebreak winner completes as initiator; the loser's crossed
        // message 1 arrives within the 1 s grace window. The winner drops
        // it but schedules one bounded recovery.
        let a_key = secret_from(0xA1);
        let b_key = secret_from(0xB2);
        let a_id = PeerId::from_public_key(&public_bytes(&a_key));
        let b_id = PeerId::from_public_key(&public_bytes(&b_key));
        // Pick the lexicographically smaller side as the winner "w".
        let (w_key, l_key, w_id, l_id) = if a_id.short().as_str() < b_id.short().as_str() {
            (a_key, b_key, a_id.clone(), b_id.clone())
        } else {
            (b_key, a_key, b_id.clone(), a_id.clone())
        };
        let (mut w, w_clock) = manager(w_key, &w_id.to_string(), 9_000);
        let (mut l, _) = manager(l_key, &l_id.to_string(), 9_000);

        let m1 = w.initiate_handshake(&l_id).unwrap();
        let m2 = l
            .handle_incoming_with_result(&w_id, &m1)
            .unwrap()
            .response
            .unwrap();
        let out23 = w.handle_incoming_with_result(&l_id, &m2).unwrap();
        assert!(out23.established); // winner completed as initiator
        let m3 = out23.response.unwrap();
        l.handle_incoming_with_result(&w_id, &m3).unwrap();

        // Loser sends a crossed message 1 (peer restart) that the winner
        // receives at t+0.4 s — inside the 1 s grace.
        w_clock.store(9_400, Ordering::SeqCst);
        let initiation = l
            .initiate_reconnect_handshake(&w_id, true, || Ok(()))
            .unwrap();
        let m1l = l.claim_handshake_initiation(&initiation, &w_id).unwrap();
        let out = w.handle_incoming_with_result(&l_id, &m1l).unwrap();
        assert!(out.response.is_none());
        assert!(w.session_generation(&l_id).is_some());

        // The suppressed-recovery timer fires after the grace window.
        w_clock.store(9_000 + 1_400, Ordering::SeqCst);
        w.handle_deadlines(9_000 + 1_400);
        let events = w.take_events();
        assert!(
            events
                .iter()
                .any(|e| matches!(e, ManagerEvent::HandshakeRecoveryRequired { .. })),
            "expected a bounded recovery, got {events:?}"
        );
    }

    #[test]
    fn sessions_needing_rekey_by_age() {
        let a_key = secret_from(0xA1);
        let b_key = secret_from(0xB2);
        let a_id = PeerId::from_public_key(&public_bytes(&a_key));
        let b_id = PeerId::from_public_key(&public_bytes(&b_key));
        let (mut a, a_clock) = manager(a_key, &a_id.to_string(), 3_000);
        let (mut b, _) = manager(b_key, &b_id.to_string(), 3_000);
        let m1 = a.initiate_handshake(&b_id).unwrap();
        let m2 = b.handle_incoming_with_result(&a_id, &m1).unwrap().response.unwrap();
        let out23 = a.handle_incoming_with_result(&b_id, &m2).unwrap();
        let m3 = out23.response.unwrap();
        b.handle_incoming_with_result(&a_id, &m3).unwrap();
        assert!(a.sessions_needing_rekey().is_empty());

        // Cross the 90 % age threshold (77 760 s).
        a_clock.store(3_000 + 78_000_000, Ordering::SeqCst);
        let due = a.sessions_needing_rekey();
        assert_eq!(due.len(), 1);
        assert_eq!(due[0].0, b_id);
    }
}
