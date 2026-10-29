//! `NoiseHandshakeState` — the XX / IK / NK / X handshake state machine.
//!
//! XX (interactive): `-> e | <- e, ee, s, es | -> s, se` — message sizes
//! 32 / 96 / 64 bytes with empty payloads.
//! X (store-and-forward seal): a single one-way message `-> e, es, s, ss`
//! against the recipient's static key (or a one-time prekey), with a
//! domain-separation prologue.

use x25519_dalek::{PublicKey, StaticSecret};

use super::symmetric::SymmetricState;
use super::NoiseError;

/// Public-key bytes of an X25519 secret.
pub fn public_bytes(secret: &StaticSecret) -> [u8; 32] {
    *PublicKey::from(secret).as_bytes()
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum NoisePattern {
    Xx,
    Ik,
    Nk,
    X,
}

impl NoisePattern {
    pub fn protocol_name(&self) -> &'static str {
        match self {
            NoisePattern::Xx => "Noise_XX_25519_ChaChaPoly_SHA256",
            NoisePattern::Ik => "Noise_IK_25519_ChaChaPoly_SHA256",
            NoisePattern::Nk => "Noise_NK_25519_ChaChaPoly_SHA256",
            NoisePattern::X => "Noise_X_25519_ChaChaPoly_SHA256",
        }
    }

    fn message_patterns(&self) -> &'static [&'static [Token]] {
        match self {
            NoisePattern::Xx => &[&[Token::E], &[Token::E, Token::Ee, Token::S, Token::Es], &[Token::S, Token::Se]],
            NoisePattern::Ik => &[&[Token::E, Token::Es, Token::S, Token::Ss], &[Token::E, Token::Ee, Token::Se]],
            NoisePattern::Nk => &[&[Token::E, Token::Es], &[Token::E, Token::Ee]],
            NoisePattern::X => &[&[Token::E, Token::Es, Token::S, Token::Ss]],
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum NoiseRole {
    Initiator,
    Responder,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Token {
    E,
    S,
    Ee,
    Es,
    Se,
    Ss,
}

/// Known low-order / invalid Curve25519 points (bitchat's blacklist).
const LOW_ORDER_POINTS: [&[u8; 32]; 9] = [
    &[0x00; 32],
    &[0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00],
    &[0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01],
    &[0xe0, 0xeb, 0x7a, 0x7c, 0x3b, 0x41, 0xb8, 0xae, 0x16, 0x56, 0xe3, 0xfa, 0xf1, 0x9f, 0xc4, 0x6a, 0xda, 0x09, 0x8d, 0xeb, 0x9c, 0x32, 0xb1, 0xfd, 0x86, 0x62, 0x05, 0x16, 0x5f, 0x49, 0xb8, 0x00],
    &[0x5f, 0x9c, 0x95, 0xbc, 0xa3, 0x50, 0x8c, 0x24, 0xb1, 0xd0, 0xb1, 0x55, 0x9c, 0x83, 0xef, 0x5b, 0x04, 0x44, 0x5c, 0xc4, 0x58, 0x1c, 0x8e, 0x86, 0xd8, 0x22, 0x4e, 0xdd, 0xd0, 0x9f, 0x11, 0x57],
    &[0xFF; 32],
    &[0xda, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF],
    &[0xdb, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF],
    &[0xed, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF],
];

/// Validate a received Curve25519 public key: 32 bytes, constant-time
/// all-zero rejection, constant-time blacklist comparison.
pub fn validate_public_key(key_data: &[u8]) -> Result<[u8; 32], NoiseError> {
    if key_data.len() != 32 {
        return Err(NoiseError::InvalidPublicKey);
    }
    // Constant-time all-zero check.
    let mut acc: u8 = 0;
    for &b in key_data {
        acc |= b;
    }
    if acc == 0 {
        return Err(NoiseError::InvalidPublicKey);
    }
    // Constant-time blacklist check (no early exit).
    let mut found_bad = false;
    for bad in &LOW_ORDER_POINTS {
        let mut diff: u8 = 0;
        for i in 0..32 {
            diff |= key_data[i] ^ bad[i];
        }
        if diff == 0 {
            found_bad = true;
        }
    }
    if found_bad {
        return Err(NoiseError::InvalidPublicKey);
    }
    let mut out = [0u8; 32];
    out.copy_from_slice(key_data);
    Ok(out)
}

pub struct HandshakeState {
    role: NoiseRole,
    pattern: NoisePattern,
    symmetric_state: SymmetricState,

    local_static: Option<StaticSecret>,
    local_static_public: Option<[u8; 32]>,
    local_ephemeral: Option<StaticSecret>,
    local_ephemeral_public: Option<[u8; 32]>,

    remote_static: Option<[u8; 32]>,
    remote_ephemeral: Option<[u8; 32]>,

    current_pattern: usize,

    /// Test seam: fixed ephemeral keys for golden vectors.
    predetermined_ephemeral: Option<StaticSecret>,
}

impl HandshakeState {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        role: NoiseRole,
        pattern: NoisePattern,
        local_static: Option<StaticSecret>,
        remote_static: Option<[u8; 32]>,
        prologue: &[u8],
    ) -> Self {
        Self::with_predetermined_ephemeral(role, pattern, local_static, remote_static, prologue, None)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn with_predetermined_ephemeral(
        role: NoiseRole,
        pattern: NoisePattern,
        local_static: Option<StaticSecret>,
        remote_static: Option<[u8; 32]>,
        prologue: &[u8],
        predetermined_ephemeral: Option<StaticSecret>,
    ) -> Self {
        let local_static_public = local_static.as_ref().map(public_bytes);
        let mut symmetric_state = SymmetricState::new(pattern.protocol_name());

        // Pre-message mixing: the prologue is ALWAYS mixed (even when empty),
        // then IK/NK/X mix the responder's static key into the hash
        // (initiator mixes the remote static, responder mixes its own).
        symmetric_state.mix_hash(prologue);
        match pattern {
            NoisePattern::Xx => {}
            NoisePattern::Ik | NoisePattern::Nk | NoisePattern::X => {
                match role {
                    NoiseRole::Initiator => {
                        if let Some(rs) = remote_static {
                            symmetric_state.mix_hash(&rs);
                        }
                    }
                    NoiseRole::Responder => {
                        if let Some(ls) = local_static_public {
                            symmetric_state.mix_hash(&ls);
                        }
                    }
                }
            }
        }

        HandshakeState {
            role,
            pattern,
            symmetric_state,
            local_static,
            local_static_public,
            local_ephemeral: None,
            local_ephemeral_public: None,
            remote_static,
            remote_ephemeral: None,
            current_pattern: 0,
            predetermined_ephemeral,
        }
    }

    fn patterns(&self) -> &'static [&'static [Token]] {
        self.pattern.message_patterns()
    }

    pub fn is_handshake_complete(&self) -> bool {
        self.current_pattern >= self.patterns().len()
    }

    pub fn get_handshake_hash(&self) -> Vec<u8> {
        self.symmetric_state.get_handshake_hash().to_vec()
    }

    pub fn get_remote_static_public_key(&self) -> Option<[u8; 32]> {
        self.remote_static
    }

    fn dh(local: &StaticSecret, remote: &[u8; 32]) -> [u8; 32] {
        local.diffie_hellman(&PublicKey::from(*remote)).to_bytes()
    }

    fn perform_dh(&mut self, token: Token) -> Result<(), NoiseError> {
        let (use_local_ephemeral, use_remote_static): (bool, bool) = match (token, self.role) {
            (Token::Ee, _) => (true, false), // DH(e, re)
            (Token::Es, NoiseRole::Initiator) => (true, true), // DH(e, rs)
            (Token::Es, NoiseRole::Responder) => (false, false), // DH(s, re)
            (Token::Se, NoiseRole::Initiator) => (false, false), // DH(s, re)
            (Token::Se, NoiseRole::Responder) => (true, true), // DH(e, rs)
            (Token::Ss, _) => (false, true), // DH(s, rs)
            (Token::E | Token::S, _) => return Ok(()),
        };

        let local = if use_local_ephemeral {
            self.local_ephemeral.as_ref()
        } else {
            self.local_static.as_ref()
        }
        .ok_or(NoiseError::MissingKeys)?;
        let remote = if use_remote_static {
            self.remote_static.as_ref()
        } else {
            self.remote_ephemeral.as_ref()
        }
        .ok_or(NoiseError::MissingKeys)?;

        let mut shared = Self::dh(local, remote);
        self.symmetric_state.mix_key(&shared);
        // Scrub our copy of the shared secret immediately after mixing.
        zeroize::Zeroize::zeroize(&mut shared);
        Ok(())
    }

    /// Write the next handshake message (with payload).
    pub fn write_message(&mut self, payload: &[u8]) -> Result<Vec<u8>, NoiseError> {
        let patterns = self.patterns();
        if self.current_pattern >= patterns.len() {
            return Err(NoiseError::HandshakeComplete);
        }
        let mut message = Vec::new();

        for &token in patterns[self.current_pattern] {
            match token {
                Token::E => {
                    let ephemeral = self
                        .predetermined_ephemeral
                        .take()
                        .unwrap_or_else(|| StaticSecret::random_from_rng(rand_core::OsRng));
                    let public = public_bytes(&ephemeral);
                    self.local_ephemeral = Some(ephemeral);
                    self.local_ephemeral_public = Some(public);
                    message.extend_from_slice(&public);
                    self.symmetric_state.mix_hash(&public);
                }
                Token::S => {
                    let static_public = self.local_static_public.ok_or(NoiseError::MissingLocalStaticKey)?;
                    let encrypted = self.symmetric_state.encrypt_and_hash(&static_public)?;
                    message.extend_from_slice(&encrypted);
                }
                Token::Ee | Token::Es | Token::Se | Token::Ss => self.perform_dh(token)?,
            }
        }

        let encrypted_payload = self.symmetric_state.encrypt_and_hash(payload)?;
        message.extend_from_slice(&encrypted_payload);

        self.current_pattern += 1;
        Ok(message)
    }

    /// Read (consume) the next handshake message, returning its payload.
    pub fn read_message(&mut self, message: &[u8]) -> Result<Vec<u8>, NoiseError> {
        let patterns = self.patterns();
        if self.current_pattern >= patterns.len() {
            return Err(NoiseError::HandshakeComplete);
        }
        let mut buffer = message;

        for &token in patterns[self.current_pattern] {
            match token {
                Token::E => {
                    if buffer.len() < 32 {
                        return Err(NoiseError::InvalidMessage);
                    }
                    let (ephemeral, rest) = buffer.split_at(32);
                    let ephemeral = validate_public_key(ephemeral).map_err(|_| NoiseError::InvalidMessage)?;
                    self.remote_ephemeral = Some(ephemeral);
                    buffer = rest;
                    self.symmetric_state.mix_hash(&ephemeral);
                }
                Token::S => {
                    // 32 bytes, or 48 (32 + 16-byte tag) when a key is set.
                    let key_length = if self.symmetric_state.has_cipher_key() { 48 } else { 32 };
                    if buffer.len() < key_length {
                        return Err(NoiseError::InvalidMessage);
                    }
                    let (static_data, rest) = buffer.split_at(key_length);
                    buffer = rest;
                    let decrypted = self
                        .symmetric_state
                        .decrypt_and_hash(static_data)
                        .map_err(|_| NoiseError::AuthenticationFailure)?;
                    let key = validate_public_key(&decrypted).map_err(|_| NoiseError::AuthenticationFailure)?;
                    self.remote_static = Some(key);
                }
                Token::Ee | Token::Es | Token::Se | Token::Ss => self.perform_dh(token)?,
            }
        }

        let payload = self.symmetric_state.decrypt_and_hash(buffer)?;
        self.current_pattern += 1;
        Ok(payload)
    }

    /// Finalize: capture the handshake hash (channel binding) **before**
    /// `Split()` clears the symmetric state. Initiator sends with c1 and
    /// receives with c2; the responder the other way around.
    pub fn get_transport_ciphers(
        self,
        use_extracted_nonce: bool,
    ) -> Result<(CipherState, CipherState, Vec<u8>), NoiseError> {
        if !self.is_handshake_complete() {
            return Err(NoiseError::HandshakeNotComplete);
        }
        let handshake_hash = self.symmetric_state.get_handshake_hash().to_vec();
        let (c1, c2) = self.symmetric_state.split(use_extracted_nonce);
        Ok(match self.role {
            NoiseRole::Initiator => (c1, c2, handshake_hash),
            NoiseRole::Responder => (c2, c1, handshake_hash),
        })
    }
}

use super::cipher::CipherState;

#[cfg(test)]
mod tests {
    use super::*;

    fn static_from(seed: [u8; 32]) -> StaticSecret {
        StaticSecret::from(seed)
    }

    #[test]
    fn xx_handshake_full_flow() {
        let alice = static_from([0xA1; 32]);
        let bob = static_from([0xB2; 32]);

        let bob_pub = public_bytes(&bob);
        let alice_pub = public_bytes(&alice);
        let mut init = HandshakeState::new(
            NoiseRole::Initiator,
            NoisePattern::Xx,
            Some(alice),
            None,
            &[],
        );
        let mut resp = HandshakeState::new(
            NoiseRole::Responder,
            NoisePattern::Xx,
            Some(bob),
            None,
            &[],
        );

        // -> e
        let m1 = init.write_message(&[]).unwrap();
        assert_eq!(m1.len(), 32, "XX message 1 is the bare ephemeral");
        // <- e, ee, s, es
        let m2 = resp.process_as_responder(&m1).unwrap();
        assert_eq!(m2.len(), 96, "e(32) + enc s(48) + empty payload tag(16)");
        // -> s, se
        let m3 = init.read_message(&m2).unwrap();
        assert_eq!(m3.len(), 0);
        let m3_out = init.write_message(&[]).unwrap();
        assert_eq!(m3_out.len(), 64, "enc s(48) + empty payload tag(16)");
        let payload = resp.read_message(&m3_out).unwrap();
        assert_eq!(payload.len(), 0);

        assert!(init.is_handshake_complete() && resp.is_handshake_complete());
        assert_eq!(init.get_remote_static_public_key(), Some(bob_pub));
        assert_eq!(resp.get_remote_static_public_key(), Some(alice_pub));

        // Transport: both directions, extracted-nonce framing.
        let (mut i_send, mut i_recv, _h1) = init.get_transport_ciphers(true).unwrap();
        let (mut r_send, mut r_recv, _h2) = resp.get_transport_ciphers(true).unwrap();
        let ct = i_send.encrypt(b"ping from alice", b"").unwrap();
        assert_eq!(r_recv.decrypt(&ct, b"").unwrap(), b"ping from alice");
        let ct2 = r_send.encrypt(b"pong from bob", b"").unwrap();
        assert_eq!(i_recv.decrypt(&ct2, b"").unwrap(), b"pong from bob");
        // Same handshake hash on both sides (channel binding).
        assert_eq!(hex::encode(_h1), hex::encode(_h2));
    }

    #[test]
    fn x_pattern_seal_open() {
        // One-way X seal against the recipient's static key (courier path).
        let sender = static_from([0x51; 32]);
        let recipient = static_from([0x52; 32]);
        let recipient_pub = public_bytes(&recipient);
        let sender_pub = public_bytes(&sender);

        let mut seal = HandshakeState::new(
            NoiseRole::Initiator,
            NoisePattern::X,
            Some(sender),
            Some(recipient_pub),
            b"bitchat-courier-v1",
        );
        let message = seal.write_message(b"sealed mail").unwrap();
        // e(32) + enc s(48) + enc payload(11+16)
        assert_eq!(message.len(), 32 + 48 + 11 + 16);

        let mut open = HandshakeState::new(
            NoiseRole::Responder,
            NoisePattern::X,
            Some(recipient),
            None,
            b"bitchat-courier-v1",
        );
        let payload = open.read_message(&message).unwrap();
        assert_eq!(payload, b"sealed mail");
        assert_eq!(open.get_remote_static_public_key(), Some(sender_pub));
        // Prologue mismatch must fail authentication.
        let mut bad = HandshakeState::new(
            NoiseRole::Responder,
            NoisePattern::X,
            Some(static_from([0x52; 32])),
            None,
            b"wrong-prologue",
        );
        assert!(bad.read_message(&message).is_err());
    }

    #[test]
    fn low_order_keys_rejected() {
        assert!(validate_public_key(&[0u8; 32]).is_err());
        assert!(validate_public_key(&[0xFF; 32]).is_err());
        assert!(validate_public_key(&[0x01; 33]).is_err());
        let mut ok = [0x42u8; 32];
        ok[0] = 0x09; // a valid-looking point
        assert!(validate_public_key(&ok).is_ok());
    }

    // Helper: read then (if the handshake continues) write the response.
    impl HandshakeState {
        fn process_as_responder(&mut self, incoming: &[u8]) -> Result<Vec<u8>, NoiseError> {
            self.read_message(incoming)?;
            if self.is_handshake_complete() {
                Ok(Vec::new())
            } else {
                self.write_message(&[])
            }
        }
    }
}
