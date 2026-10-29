//! `NoiseCipherState` — ChaCha20-Poly1305 AEAD with bitchat's nonce management
//! and sliding-window replay protection.
//!
//! * AEAD nonce (12 bytes): `00 00 00 00 ‖ u64_le(counter)` — bytes 4–12 hold
//!   the counter little-endian.
//! * Encrypt guard: counter ≤ 2³²−2 (`nonceExceeded` beyond).
//! * Transport mode (`extracted_nonce = true`): output is
//!   `u32_be(nonce) ‖ ciphertext ‖ tag(16)`; decrypt validates the wire nonce
//!   against a 1024-entry sliding window and rejects replays.
//! * Handshake mode: implicit counter, output `ciphertext ‖ tag`.
//! * State (window + counter) advances only after **successful** decryption
//!   (atomic rollback on failure).

use chacha20poly1305::aead::{Aead, Payload};
use chacha20poly1305::{ChaCha20Poly1305, Key, KeyInit, Nonce};
use zeroize::Zeroize;

use super::NoiseError;

const NONCE_SIZE_BYTES: usize = 4;
const REPLAY_WINDOW_SIZE: u64 = 1024;
const REPLAY_WINDOW_BYTES: usize = (REPLAY_WINDOW_SIZE as usize) / 8; // 128
const NONCE_CEILING: u64 = u32::MAX as u64 - 1; // 2^32 - 2

pub struct CipherState {
    key: Option<[u8; 32]>,
    nonce: u64,
    use_extracted_nonce: bool,
    // Sliding-window replay protection (transport mode only).
    highest_received_nonce: u64,
    replay_window: [u8; REPLAY_WINDOW_BYTES],
}

impl Default for CipherState {
    fn default() -> Self {
        Self::new()
    }
}

impl CipherState {
    pub fn new() -> Self {
        CipherState {
            key: None,
            nonce: 0,
            use_extracted_nonce: false,
            highest_received_nonce: 0,
            replay_window: [0u8; REPLAY_WINDOW_BYTES],
        }
    }

    pub fn with_key(key: [u8; 32], use_extracted_nonce: bool) -> Self {
        CipherState {
            key: Some(key),
            nonce: 0,
            use_extracted_nonce,
            highest_received_nonce: 0,
            replay_window: [0u8; REPLAY_WINDOW_BYTES],
        }
    }

    pub fn initialize_key(&mut self, key: [u8; 32]) {
        self.key = Some(key);
        self.nonce = 0;
    }

    pub fn has_key(&self) -> bool {
        self.key.is_some()
    }

    /// The AEAD nonce for a counter: 12 bytes, counter LE in bytes 4–12.
    fn aead_nonce(counter: u64) -> Nonce {
        let mut n = [0u8; 12];
        n[4..12].copy_from_slice(&counter.to_le_bytes());
        Nonce::from(n)
    }

    // ---- sliding window ---------------------------------------------------

    fn is_valid_nonce(&self, received: u64) -> bool {
        // Safe arithmetic: reject anything at or below highest − 1024.
        if self.highest_received_nonce >= REPLAY_WINDOW_SIZE
            && received <= self.highest_received_nonce - REPLAY_WINDOW_SIZE
        {
            return false; // too old, outside the window
        }
        if received > self.highest_received_nonce {
            return true; // always accept newer nonces
        }
        let offset = (self.highest_received_nonce - received) as usize;
        let byte_index = offset / 8;
        let bit_index = offset % 8;
        (self.replay_window[byte_index] & (1 << bit_index)) == 0 // not yet seen
    }

    fn mark_nonce_as_seen(&mut self, received: u64) {
        if received > self.highest_received_nonce {
            let shift = (received - self.highest_received_nonce) as usize;
            if shift >= REPLAY_WINDOW_SIZE as usize {
                self.replay_window = [0u8; REPLAY_WINDOW_BYTES];
            } else {
                // Shift the window right by `shift` bits (LSB = most recent).
                let mut new_window = [0u8; REPLAY_WINDOW_BYTES];
                for i in (0..REPLAY_WINDOW_BYTES).rev() {
                    let source_byte_index = i as isize - (shift / 8) as isize;
                    if source_byte_index < 0 {
                        continue;
                    }
                    let sbi = source_byte_index as usize;
                    let mut new_byte = self.replay_window[sbi] >> (shift % 8);
                    if !shift.is_multiple_of(8) && sbi > 0 {
                        new_byte |= self.replay_window[sbi - 1] << (8 - shift % 8);
                    }
                    new_window[i] = new_byte;
                }
                self.replay_window = new_window;
            }
            self.highest_received_nonce = received;
            self.replay_window[0] |= 1; // mark most recent bit as seen
        } else {
            let offset = (self.highest_received_nonce - received) as usize;
            let byte_index = offset / 8;
            let bit_index = offset % 8;
            self.replay_window[byte_index] |= 1 << bit_index;
        }
    }

    // ---- AEAD -------------------------------------------------------------

    pub fn encrypt(
        &mut self,
        plaintext: &[u8],
        associated_data: &[u8],
    ) -> Result<Vec<u8>, NoiseError> {
        let key = self.key.ok_or(NoiseError::UninitializedCipher)?;
        let current_nonce = self.nonce;
        if current_nonce > NONCE_CEILING {
            return Err(NoiseError::NonceExceeded);
        }
        let cipher = ChaCha20Poly1305::new(Key::from_slice(&key));
        let ct = cipher
            .encrypt(
                &Self::aead_nonce(current_nonce),
                Payload {
                    msg: plaintext,
                    aad: associated_data,
                },
            )
            .map_err(|_| NoiseError::AuthenticationFailure)?;
        self.nonce += 1;

        if self.use_extracted_nonce {
            let mut out = Vec::with_capacity(NONCE_SIZE_BYTES + ct.len());
            out.extend_from_slice(&(current_nonce as u32).to_be_bytes());
            out.extend_from_slice(&ct);
            Ok(out)
        } else {
            Ok(ct)
        }
    }

    pub fn decrypt(
        &mut self,
        ciphertext: &[u8],
        associated_data: &[u8],
    ) -> Result<Vec<u8>, NoiseError> {
        let key = self.key.ok_or(NoiseError::UninitializedCipher)?;
        if ciphertext.len() < 16 {
            return Err(NoiseError::InvalidCiphertext);
        }

        let (encrypted, tag, wire_nonce): (&[u8], &[u8], Option<u64>) = if self.use_extracted_nonce
        {
            if ciphertext.len() < NONCE_SIZE_BYTES {
                return Err(NoiseError::InvalidCiphertext);
            }
            let (nonce_bytes, rest) = ciphertext.split_at(NONCE_SIZE_BYTES);
            let extracted = u32::from_be_bytes([
                nonce_bytes[0],
                nonce_bytes[1],
                nonce_bytes[2],
                nonce_bytes[3],
            ]) as u64;
            if !self.is_valid_nonce(extracted) {
                return Err(NoiseError::ReplayDetected);
            }
            if rest.len() < 16 {
                return Err(NoiseError::InvalidCiphertext);
            }
            let split = rest.len() - 16;
            (&rest[..split], &rest[split..], Some(extracted))
        } else {
            let split = ciphertext.len() - 16;
            (&ciphertext[..split], &ciphertext[split..], None)
        };

        let decryption_nonce = wire_nonce.unwrap_or(self.nonce);
        let cipher = ChaCha20Poly1305::new(Key::from_slice(&key));
        let mut combined = Vec::with_capacity(encrypted.len() + 16);
        combined.extend_from_slice(encrypted);
        combined.extend_from_slice(tag);

        match cipher.decrypt(
            &Self::aead_nonce(decryption_nonce),
            Payload {
                msg: &combined,
                aad: associated_data,
            },
        ) {
            Ok(plaintext) => {
                // Atomic state update: only after successful decryption.
                if self.use_extracted_nonce {
                    self.mark_nonce_as_seen(decryption_nonce);
                }
                self.nonce += 1;
                Ok(plaintext)
            }
            Err(_) => Err(NoiseError::AuthenticationFailure),
        }
    }

    /// Zeroize sensitive state.
    pub fn clear_sensitive_data(&mut self) {
        if let Some(ref mut k) = self.key {
            k.zeroize();
        }
        self.key = None;
        self.nonce = 0;
        self.highest_received_nonce = 0;
        self.replay_window = [0u8; REPLAY_WINDOW_BYTES];
    }

    #[cfg(test)]
    pub fn set_nonce_for_testing(&mut self, nonce: u64) {
        self.nonce = nonce;
    }

    #[cfg(test)]
    pub fn current_nonce(&self) -> u64 {
        self.nonce
    }
}

impl Drop for CipherState {
    fn drop(&mut self) {
        self.clear_sensitive_data();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key() -> [u8; 32] {
        [0x42; 32]
    }

    #[test]
    fn encrypt_decrypt_round_trip_handshake_mode() {
        let mut c = CipherState::with_key(key(), false);
        let ct = c.encrypt(b"hello", b"").unwrap();
        assert_eq!(ct.len(), 5 + 16);
        let mut d = CipherState::with_key(key(), false);
        assert_eq!(d.decrypt(&ct, b"").unwrap(), b"hello");
    }

    #[test]
    fn extracted_nonce_framing() {
        let mut c = CipherState::with_key(key(), true);
        let ct = c.encrypt(b"world", b"").unwrap();
        assert_eq!(ct.len(), 4 + 5 + 16);
        assert_eq!(&ct[..4], &0u32.to_be_bytes()); // first nonce = 0, BE
        let mut d = CipherState::with_key(key(), true);
        assert_eq!(d.decrypt(&ct, b"").unwrap(), b"world");
        // Second message carries nonce 1.
        let ct2 = c.encrypt(b"again", b"").unwrap();
        assert_eq!(&ct2[..4], &1u32.to_be_bytes());
        assert_eq!(d.decrypt(&ct2, b"").unwrap(), b"again");
    }

    #[test]
    fn replay_rejected() {
        let mut c = CipherState::with_key(key(), true);
        let ct = c.encrypt(b"once", b"").unwrap();
        let mut d = CipherState::with_key(key(), true);
        assert!(d.decrypt(&ct, b"").is_ok());
        assert_eq!(d.decrypt(&ct, b""), Err(NoiseError::ReplayDetected));
    }

    #[test]
    fn out_of_order_within_window_accepted() {
        // Encrypt nonces 0..5; deliver 3, then 0, then 5 — all valid (skips allowed).
        let mut c = CipherState::with_key(key(), true);
        let cts: Vec<Vec<u8>> = (0..5u64)
            .map(|i| c.encrypt(&[i as u8], b"").unwrap())
            .collect();
        let mut d = CipherState::with_key(key(), true);
        assert!(d.decrypt(&cts[3], b"").is_ok());
        assert!(d.decrypt(&cts[0], b"").is_ok());
        assert!(d.decrypt(&cts[3], b"").is_err()); // duplicate within window
        assert!(d.decrypt(&cts[4], b"").is_ok());
    }

    #[test]
    fn too_old_rejected_after_window_slide() {
        let mut c = CipherState::with_key(key(), true);
        let first = c.encrypt(b"first", b"").unwrap();
        for _ in 0..1030 {
            c.encrypt(b"x", b"").unwrap();
        }
        let mut d = CipherState::with_key(key(), true);
        // Deliver a very fresh nonce first, pushing the window forward.
        let fresh = c.encrypt(b"fresh", b"").unwrap();
        assert!(d.decrypt(&fresh, b"").is_ok());
        // Nonce 0 is now more than 1024 behind the highest → rejected.
        assert_eq!(d.decrypt(&first, b""), Err(NoiseError::ReplayDetected));
    }

    #[test]
    fn aad_mismatch_fails_without_state_advance() {
        let mut c = CipherState::with_key(key(), false);
        let ct = c.encrypt(b"data", b"aad-a").unwrap();
        let mut d = CipherState::with_key(key(), false);
        assert!(d.decrypt(&ct, b"aad-b").is_err());
        // Failure must not consume the internal counter.
        assert_eq!(d.current_nonce(), 0);
        assert!(d.decrypt(&ct, b"aad-a").is_ok());
    }

    #[test]
    fn tampered_ciphertext_fails() {
        let mut c = CipherState::with_key(key(), true);
        let mut ct = c.encrypt(b"payload", b"").unwrap();
        let last = ct.len() - 1;
        ct[last] ^= 1;
        let mut d = CipherState::with_key(key(), true);
        assert_eq!(d.decrypt(&ct, b""), Err(NoiseError::AuthenticationFailure));
    }

    #[test]
    fn nonce_ceiling_enforced() {
        let mut c = CipherState::with_key(key(), false);
        c.set_nonce_for_testing(u32::MAX as u64 - 1);
        assert!(c.encrypt(b"x", b"").is_ok());
        assert_eq!(c.encrypt(b"x", b""), Err(NoiseError::NonceExceeded));
    }
}
