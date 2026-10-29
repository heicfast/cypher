//! Noise protocol stack — exact port of bitchat's implementation.
//!
//! * Interactive sessions: `Noise_XX_25519_ChaChaPoly_SHA256`
//! * Store-and-forward seals: one-way Noise `X` with domain-separation
//!   prologues (`bitchat-courier-v1`, `bitchat-prekey-v1 ‖ u32_be(id)`)
//! * Transport framing: `[4-byte BE nonce][ChaCha20-Poly1305 ct][16-byte tag]`
//!   with empty AD, plus a 1024-entry sliding-window replay filter.
//!
//! Deviations from the official Noise spec (all deliberate, all replicated
//! from bitchat for wire compatibility):
//! 1. Explicit 4-byte big-endian nonce prefix on transport messages
//! 2. 1024-entry sliding-window replay filter per receive direction
//! 3. Nonce ceiling 2³²−2 (not 2⁶⁴−1)
//! 4. Empty AD on transport messages (handshake AD = transcript hash, standard)
//! 5. Unconditional `MixHash(prologue)` even when the prologue is empty
//! 6. Low-order-point blacklist validation on received public keys
//! 7. Noise `X` with prologues as the store-and-forward seal (prekey FS scheme)

pub mod cipher;
pub mod handshake;
pub mod rate_limiter;
pub mod session;
pub mod session_manager;
pub mod symmetric;

pub use cipher::CipherState;
pub use handshake::{HandshakeState, NoisePattern, NoiseRole};
pub use rate_limiter::NoiseRateLimiter;
pub use session::{NoiseSession, NoiseSessionState};

/// Errors, mirroring bitchat's `NoiseError` / `NoiseSessionError` /
/// `NoiseSecurityError` enums.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum NoiseError {
    UninitializedCipher,
    InvalidCiphertext,
    HandshakeComplete,
    HandshakeNotComplete,
    MissingLocalStaticKey,
    MissingKeys,
    InvalidMessage,
    AuthenticationFailure,
    InvalidPublicKey,
    ReplayDetected,
    NonceExceeded,
    // NoiseSessionError
    InvalidState,
    NotEstablished,
    SessionNotFound,
    AlreadyEstablished,
    PeerIdentityMismatch,
    // Manager-level
    /// The generation is established but its transport state is not yet
    /// installed by the caller (Swift: transportGenerationNotReady).
    TransportGenerationNotReady,
    /// The manager owns recovery for this failure; the caller must not
    /// spin its own retries (Swift: NoiseManagedHandshakeFailure).
    ManagedHandshakeFailure(Box<NoiseError>),
    // NoiseSecurityError
    MessageTooLarge,
    SessionExpired,
    SessionExhausted,
    RateLimitExceeded,
}

impl std::fmt::Display for NoiseError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{self:?}")
    }
}

impl std::error::Error for NoiseError {}

/// HKDF exactly as bitchat implements it (RFC 5869 with HMAC-SHA256):
/// `temp_key = HMAC(ck, ikm)`; `out_i = HMAC(temp_key, out_{i-1} ‖ [i])`.
pub fn hkdf(chaining_key: &[u8], input_key_material: &[u8], num_outputs: usize) -> Vec<Vec<u8>> {
    let temp_key = crate::util::hmac_sha256(chaining_key, input_key_material);
    let mut outputs = Vec::with_capacity(num_outputs);
    let mut current: Vec<u8> = Vec::new();
    for i in 1..=num_outputs {
        let mut input = current.clone();
        input.push(i as u8);
        current = crate::util::hmac_sha256(&temp_key, &input).to_vec();
        outputs.push(current.clone());
    }
    outputs
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hkdf_rfc5869_case_1() {
        // RFC 5869 Test Case 1 (SHA-256)
        let ikm = hex::decode("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b").unwrap();
        // bitchat's HKDF takes (ck=chaining key as HMAC key, ikm as data);
        // RFC 5869 case 1 uses a salt of 0x00*13 as the HMAC key.
        let salt = [0u8; 13];
        let out = hkdf(&salt, &ikm, 3);
        assert_eq!(
            hex::encode(&out[0]),
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d"
        );
    }
}
