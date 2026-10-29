//! `NoiseSymmetricState` — key derivation, transcript hashing, and the
//! chaining key, per the Noise spec with bitchat's exact HKDF.

use super::cipher::CipherState;
use super::hkdf;
use zeroize::Zeroize;

pub struct SymmetricState {
    cipher_state: CipherState,
    chaining_key: Vec<u8>,
    hash: Vec<u8>,
}

impl SymmetricState {
    /// Initialize with a protocol name: `h = name ‖ zero-pad to 32` when the
    /// name fits in 32 bytes, else `h = SHA256(name)`; `ck = h`.
    pub fn new(protocol_name: &str) -> Self {
        let name = protocol_name.as_bytes();
        let hash = if name.len() <= 32 {
            let mut h = name.to_vec();
            h.resize(32, 0);
            h
        } else {
            crate::util::sha256(name).to_vec()
        };
        let chaining_key = hash.clone();
        SymmetricState {
            cipher_state: CipherState::new(),
            chaining_key,
            hash,
        }
    }

    /// `MixKey(ikm)`: `HKDF(ck, ikm, 2)` → ck = out[0], k = out[1];
    /// the cipher key is re-initialized (nonce reset to 0).
    pub fn mix_key(&mut self, input_key_material: &[u8]) {
        let mut output = hkdf(&self.chaining_key, input_key_material, 2);
        self.chaining_key = output[0].clone();
        let mut k = [0u8; 32];
        k.copy_from_slice(&output[1]);
        self.cipher_state.initialize_key(k);
        output[1].zeroize();
    }

    /// `MixHash(data)`: `h = SHA256(h ‖ data)`.
    pub fn mix_hash(&mut self, data: &[u8]) {
        let mut input = self.hash.clone();
        input.extend_from_slice(data);
        self.hash = crate::util::sha256(&input).to_vec();
    }

    /// `MixKeyAndHash(ikm)`: HKDF with 3 outputs (implemented for spec
    /// completeness; bitchat never calls it — no PSKs).
    pub fn mix_key_and_hash(&mut self, input_key_material: &[u8]) {
        let output = hkdf(&self.chaining_key, input_key_material, 3);
        self.chaining_key = output[0].clone();
        self.mix_hash(&output[1]);
        let mut k = [0u8; 32];
        k.copy_from_slice(&output[2]);
        self.cipher_state.initialize_key(k);
    }

    pub fn get_handshake_hash(&self) -> &[u8] {
        &self.hash
    }

    pub fn has_cipher_key(&self) -> bool {
        self.cipher_state.has_key()
    }

    /// `EncryptWithAd(h, plaintext)` then `MixHash(ciphertext)`; when no key
    /// is set the plaintext is mixed into the hash unchanged.
    pub fn encrypt_and_hash(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, super::NoiseError> {
        if self.cipher_state.has_key() {
            let ciphertext = self.cipher_state.encrypt(plaintext, &self.hash.clone())?;
            self.mix_hash(&ciphertext);
            Ok(ciphertext)
        } else {
            self.mix_hash(plaintext);
            Ok(plaintext.to_vec())
        }
    }

    /// `DecryptWithAd(h, ciphertext)` then `MixHash(ciphertext)`.
    pub fn decrypt_and_hash(&mut self, ciphertext: &[u8]) -> Result<Vec<u8>, super::NoiseError> {
        if self.cipher_state.has_key() {
            let plaintext = self.cipher_state.decrypt(ciphertext, &self.hash.clone())?;
            self.mix_hash(ciphertext);
            Ok(plaintext)
        } else {
            self.mix_hash(ciphertext);
            Ok(ciphertext.to_vec())
        }
    }

    /// `Split()`: `HKDF(ck, empty, 2)` → (c1, c2); clears the symmetric state
    /// (keys never retained after the handshake).
    pub fn split(mut self, use_extracted_nonce: bool) -> (CipherState, CipherState) {
        let output = hkdf(&self.chaining_key, &[], 2);
        let mut k1 = [0u8; 32];
        let mut k2 = [0u8; 32];
        k1.copy_from_slice(&output[0]);
        k2.copy_from_slice(&output[1]);
        let c1 = CipherState::with_key(k1, use_extracted_nonce);
        let c2 = CipherState::with_key(k2, use_extracted_nonce);
        self.clear_sensitive_data();
        (c1, c2)
    }

    /// Zeroize the chaining key, hash, and cipher state.
    pub fn clear_sensitive_data(&mut self) {
        self.chaining_key.zeroize();
        self.hash.zeroize();
        self.cipher_state.clear_sensitive_data();
    }
}

impl Drop for SymmetricState {
    fn drop(&mut self) {
        self.clear_sensitive_data();
    }
}
