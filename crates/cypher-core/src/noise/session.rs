//! `NoiseSession` — a peer session wrapping the XX handshake and transport
//! ciphers, with bitchat's `SecureNoiseSession` hard limits (24 h session
//! timeout, 10⁹ message ceiling, message-size validation, rekey heuristic).

use x25519_dalek::StaticSecret;

use super::cipher::CipherState;
#[cfg(test)]
use super::handshake::public_bytes;
use super::handshake::{HandshakeState, NoisePattern, NoiseRole};
use super::NoiseError;
use crate::consts;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum NoiseSessionState {
    Uninitialized,
    Handshaking,
    Established,
}

pub struct NoiseSession {
    pub peer_id: crate::PeerId,
    pub role: NoiseRole,
    state: NoiseSessionState,
    handshake_state: Option<HandshakeState>,
    send_cipher: Option<CipherState>,
    receive_cipher: Option<CipherState>,

    local_static_key: StaticSecret,
    remote_static_public_key: Option<[u8; 32]>,

    /// Retained handshake messages (for diagnostics/retransmission parity).
    sent_handshake_messages: Vec<Vec<u8>>,
    handshake_hash: Option<Vec<u8>>,

    // SecureNoiseSession limits.
    message_count: u64,
    session_start_ms: u128,
    last_activity_ms: u128,
    /// Monotonic wall clock (ms); injected for deterministic tests.
    now_ms: std::sync::Arc<dyn Fn() -> u64 + Send + Sync>,
}

impl NoiseSession {
    pub fn new(peer_id: crate::PeerId, role: NoiseRole, local_static_key: StaticSecret) -> Self {
        Self::with_clock(
            peer_id,
            role,
            local_static_key,
            std::sync::Arc::new(crate::util::now_ms),
        )
    }

    pub fn with_clock(
        peer_id: crate::PeerId,
        role: NoiseRole,
        local_static_key: StaticSecret,
        now_ms: std::sync::Arc<dyn Fn() -> u64 + Send + Sync>,
    ) -> Self {
        let now = now_ms() as u128;
        NoiseSession {
            peer_id,
            role,
            state: NoiseSessionState::Uninitialized,
            handshake_state: None,
            send_cipher: None,
            receive_cipher: None,
            local_static_key,
            remote_static_public_key: None,
            sent_handshake_messages: Vec::new(),
            handshake_hash: None,
            message_count: 0,
            session_start_ms: now,
            last_activity_ms: now,
            now_ms,
        }
    }

    fn now(&self) -> u128 {
        (self.now_ms)() as u128
    }

    /// Start an XX handshake. Initiators return message 1; responders return
    /// empty bytes (they speak second).
    pub fn start_handshake(&mut self) -> Result<Vec<u8>, NoiseError> {
        if self.state != NoiseSessionState::Uninitialized {
            return Err(NoiseError::InvalidState);
        }
        let mut hs = HandshakeState::new(
            self.role,
            NoisePattern::Xx,
            Some(clone_static(&self.local_static_key)),
            None,
            &[],
        );
        self.state = NoiseSessionState::Handshaking;
        if self.role == NoiseRole::Initiator {
            let message = hs.write_message(&[])?;
            self.sent_handshake_messages.push(message.clone());
            self.handshake_state = Some(hs);
            Ok(message)
        } else {
            self.handshake_state = Some(hs);
            Ok(Vec::new())
        }
    }

    /// Process an incoming handshake message. Returns the response to send
    /// (if any). On completion the session transitions to established.
    pub fn process_handshake_message(
        &mut self,
        message: &[u8],
    ) -> Result<Option<Vec<u8>>, NoiseError> {
        if self.state == NoiseSessionState::Uninitialized && self.role == NoiseRole::Responder {
            let hs = HandshakeState::new(
                self.role,
                NoisePattern::Xx,
                Some(clone_static(&self.local_static_key)),
                None,
                &[],
            );
            self.handshake_state = Some(hs);
            self.state = NoiseSessionState::Handshaking;
        }

        if self.state != NoiseSessionState::Handshaking {
            return Err(NoiseError::InvalidState);
        }
        let mut handshake = self
            .handshake_state
            .take()
            .ok_or(NoiseError::InvalidState)?;

        handshake.read_message(message)?;

        if handshake.is_handshake_complete() {
            self.finish_established(handshake)?;
            return Ok(None);
        }

        let response = handshake.write_message(&[])?;
        self.sent_handshake_messages.push(response.clone());
        if handshake.is_handshake_complete() {
            self.finish_established(handshake)?;
        } else {
            self.handshake_state = Some(handshake);
        }
        Ok(Some(response))
    }

    fn finish_established(&mut self, handshake: HandshakeState) -> Result<(), NoiseError> {
        // Capture the remote static before the handshake state is consumed.
        let remote_static = handshake
            .get_remote_static_public_key()
            .ok_or(NoiseError::MissingKeys)?;
        let (send, receive, hash) = handshake.get_transport_ciphers(true)?;
        self.send_cipher = Some(send);
        self.receive_cipher = Some(receive);
        self.remote_static_public_key = Some(remote_static);
        self.handshake_hash = Some(hash);
        self.state = NoiseSessionState::Established;
        // Reset SecureNoiseSession counters for the fresh session.
        let now = self.now();
        self.session_start_ms = now;
        self.last_activity_ms = now;
        self.message_count = 0;
        Ok(())
    }

    // ---- transport ---------------------------------------------------------

    pub fn encrypt(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, NoiseError> {
        if self.state != NoiseSessionState::Established {
            return Err(NoiseError::NotEstablished);
        }
        // SecureNoiseSession limits.
        let age_s = (self.now() - self.session_start_ms) as f64 / 1000.0;
        if age_s > consts::NOISE_SESSION_TIMEOUT_S {
            return Err(NoiseError::SessionExpired);
        }
        if self.message_count >= consts::NOISE_MAX_MESSAGES_PER_SESSION {
            return Err(NoiseError::SessionExhausted);
        }
        // Ordinary messages keep the 64 KiB ceiling; privateFile typed
        // payloads may use the framed-file budget.
        let is_private_file = matches!(plaintext.first(), Some(0x20) | Some(0x09))
            && plaintext.len() <= consts::NOISE_MAX_PRIVATE_FILE_PLAINTEXT;
        if plaintext.len() > consts::NOISE_MAX_MESSAGE_SIZE && !is_private_file {
            return Err(NoiseError::MessageTooLarge);
        }

        let cipher = self
            .send_cipher
            .as_mut()
            .ok_or(NoiseError::NotEstablished)?;
        let encrypted = cipher.encrypt(plaintext, &[])?; // transport AD is empty
        self.message_count += 1;
        self.last_activity_ms = self.now();
        Ok(encrypted)
    }

    pub fn decrypt(&mut self, ciphertext: &[u8]) -> Result<Vec<u8>, NoiseError> {
        if self.state != NoiseSessionState::Established {
            return Err(NoiseError::NotEstablished);
        }
        let age_s = (self.now() - self.session_start_ms) as f64 / 1000.0;
        if age_s > consts::NOISE_SESSION_TIMEOUT_S {
            return Err(NoiseError::SessionExpired);
        }
        let valid_size = ciphertext.len()
            <= consts::NOISE_MAX_MESSAGE_SIZE + consts::NOISE_TRANSPORT_OVERHEAD
            || ciphertext.len() <= consts::NOISE_MAX_PRIVATE_FILE_CIPHERTEXT;
        if !valid_size {
            return Err(NoiseError::MessageTooLarge);
        }
        let cipher = self
            .receive_cipher
            .as_mut()
            .ok_or(NoiseError::NotEstablished)?;
        let decrypted = cipher.decrypt(ciphertext, &[])?;
        self.last_activity_ms = self.now();
        Ok(decrypted)
    }

    // ---- state --------------------------------------------------------------

    pub fn get_state(&self) -> NoiseSessionState {
        self.state
    }

    pub fn is_established(&self) -> bool {
        self.state == NoiseSessionState::Established
    }

    pub fn is_handshaking(&self) -> bool {
        self.state == NoiseSessionState::Handshaking
    }

    pub fn get_remote_static_public_key(&self) -> Option<[u8; 32]> {
        self.remote_static_public_key
    }

    pub fn handshake_hash(&self) -> Option<&[u8]> {
        self.handshake_hash.as_deref()
    }

    pub fn message_count(&self) -> u64 {
        self.message_count
    }

    /// `SecureNoiseSession.needsRenegotiation()`: 90% message budget, idle
    /// beyond the session timeout, or 90% of the session age limit.
    pub fn needs_renegotiation(&self) -> bool {
        let now = self.now();
        let message_threshold = (consts::NOISE_MAX_MESSAGES_PER_SESSION as f64 * 0.9) as u64;
        if self.message_count >= message_threshold {
            return true;
        }
        let idle_s = (now - self.last_activity_ms) as f64 / 1000.0;
        if idle_s > consts::NOISE_SESSION_TIMEOUT_S {
            return true;
        }
        let age_s = (now - self.session_start_ms) as f64 / 1000.0;
        if age_s > consts::NOISE_SESSION_TIMEOUT_S * 0.9 {
            return true;
        }
        false
    }

    /// Wipe the session back to uninitialized (keys zeroized).
    pub fn reset(&mut self) {
        self.state = NoiseSessionState::Uninitialized;
        self.handshake_state = None;
        if let Some(ref mut c) = self.send_cipher {
            c.clear_sensitive_data();
        }
        if let Some(ref mut c) = self.receive_cipher {
            c.clear_sensitive_data();
        }
        self.send_cipher = None;
        self.receive_cipher = None;
        for m in self.sent_handshake_messages.iter_mut() {
            zeroize::Zeroize::zeroize(m);
        }
        self.sent_handshake_messages.clear();
        if let Some(ref mut h) = self.handshake_hash {
            zeroize::Zeroize::zeroize(h);
        }
        self.handshake_hash = None;
    }

    #[cfg(test)]
    pub(crate) fn debug_times(&self) -> (u128, u128, u128) {
        (self.now(), self.session_start_ms, self.last_activity_ms)
    }

}


fn clone_static(secret: &StaticSecret) -> StaticSecret {
    StaticSecret::from(secret.to_bytes())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn full_session_flow() {
        let alice_key = StaticSecret::from([0xA1; 32]);
        let bob_key = StaticSecret::from([0xB2; 32]);
        let mut alice = NoiseSession::new(
            crate::PeerId::from_public_key(&public_bytes(&alice_key)),
            NoiseRole::Initiator,
            alice_key,
        );
        let mut bob = NoiseSession::new(
            crate::PeerId::from_public_key(&public_bytes(&bob_key)),
            NoiseRole::Responder,
            bob_key,
        );

        let m1 = alice.start_handshake().unwrap();
        assert_eq!(m1.len(), 32);
        let m2 = bob.process_handshake_message(&m1).unwrap().unwrap();
        assert_eq!(m2.len(), 96);
        let m3 = alice.process_handshake_message(&m2).unwrap().unwrap();
        assert_eq!(m3.len(), 64);
        assert!(bob.process_handshake_message(&m3).unwrap().is_none());

        assert!(alice.is_established() && bob.is_established());

        let ct = alice.encrypt(b"private hello").unwrap();
        assert_eq!(bob.decrypt(&ct).unwrap(), b"private hello");
        let ct2 = bob.encrypt(b"private reply").unwrap();
        assert_eq!(alice.decrypt(&ct2).unwrap(), b"private reply");

        // Replays rejected end-to-end.
        assert_eq!(bob.decrypt(&ct), Err(NoiseError::ReplayDetected));

        // Message too large rejected.
        let big = vec![0u8; 70_000];
        assert_eq!(alice.encrypt(&big), Err(NoiseError::MessageTooLarge));
    }

    #[test]
    fn invalid_state_transitions() {
        let key = StaticSecret::from([0x11; 32]);
        let mut s = NoiseSession::new(crate::PeerId::new("aa"), NoiseRole::Initiator, key);
        assert_eq!(
            s.process_handshake_message(&[0u8; 32]),
            Err(NoiseError::InvalidState)
        );
        s.start_handshake().unwrap();
        assert_eq!(s.start_handshake(), Err(NoiseError::InvalidState));
        assert_eq!(s.encrypt(b"x"), Err(NoiseError::NotEstablished));
    }

    #[test]
    fn session_expiry_and_reset() {
        let key = StaticSecret::from([0x22; 32]);
        let mut s = NoiseSession::new(crate::PeerId::new("bb"), NoiseRole::Initiator, key);
        s.start_handshake().unwrap();
        s.reset();
        assert_eq!(s.get_state(), NoiseSessionState::Uninitialized);
        // After reset a fresh handshake is possible.
        s.start_handshake().unwrap();
    }
}
