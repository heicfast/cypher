//! Device identity: the two long-term keypairs every Cypher node owns.
//!
//! * **X25519 static key** — Noise identity; `SHA-256(public)` is the
//!   persistent fingerprint; its first 8 bytes are the on-air peer ID.
//! * **Ed25519 signing key** — announces, packet signatures, prekey bundles,
//!   vouches, verification challenges.
//!
//! Persistence is delegated to the host ([`IdentityStore`]); a file-based
//! store is provided for tests and desktop use. On Android the app supplies
//! an Android Keystore-backed implementation via JNI.

use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use x25519_dalek::{PublicKey, StaticSecret};

use crate::util::{hex_encode, sha256_fingerprint};

/// Ed25519 signature length.
pub const SIGNATURE_LENGTH: usize = 64;
/// X25519 / Ed25519 key length.
pub const KEY_LENGTH: usize = 32;

/// The device's two long-term keypairs.
#[derive(Clone)]
pub struct CypherIdentity {
    noise_static: StaticSecret,
    signing: SigningKey,
}

impl std::fmt::Debug for CypherIdentity {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("CypherIdentity")
            .field("fingerprint", &self.fingerprint())
            .finish()
    }
}

impl CypherIdentity {
    /// Generate a fresh identity with the OS CSPRNG.
    pub fn generate() -> Self {
        CypherIdentity {
            noise_static: StaticSecret::random_from_rng(rand_core::OsRng),
            signing: SigningKey::generate(&mut rand_core::OsRng),
        }
    }

    /// Restore from raw key material (32-byte X25519 secret + 32-byte
    /// Ed25519 seed).
    pub fn from_bytes(noise_static: [u8; 32], signing_seed: [u8; 32]) -> Self {
        CypherIdentity {
            noise_static: StaticSecret::from(noise_static),
            signing: SigningKey::from_bytes(&signing_seed),
        }
    }

    pub fn noise_static_secret(&self) -> &StaticSecret {
        &self.noise_static
    }

    pub fn noise_static_public(&self) -> [u8; 32] {
        *PublicKey::from(&self.noise_static).as_bytes()
    }

    pub fn signing_key(&self) -> &SigningKey {
        &self.signing
    }

    pub fn signing_public(&self) -> [u8; 32] {
        self.signing.verifying_key().to_bytes()
    }

    /// Raw key material for persistence.
    pub fn to_bytes(&self) -> ([u8; 32], [u8; 32]) {
        (self.noise_static.to_bytes(), self.signing.to_bytes())
    }

    /// Full lowercase SHA-256 fingerprint of the Noise public key — the
    /// persistent identity string across the whole system.
    pub fn fingerprint(&self) -> String {
        sha256_fingerprint(&self.noise_static_public())
    }

    /// Short on-air peer ID (first 16 hex chars of the fingerprint).
    pub fn peer_id(&self) -> crate::PeerId {
        crate::PeerId::from_public_key(&self.noise_static_public())
    }

    /// Ed25519-sign arbitrary bytes (packet preimages, bundles, vouches).
    pub fn sign(&self, data: &[u8]) -> [u8; SIGNATURE_LENGTH] {
        self.signing.sign(data).to_bytes()
    }

    /// Ed25519 verify (constant-time internally).
    pub fn verify(signature: &[u8], data: &[u8], public_key: &[u8; 32]) -> bool {
        let Ok(vk) = VerifyingKey::from_bytes(public_key) else {
            return false;
        };
        let mut sig = [0u8; 64];
        if signature.len() != 64 {
            return false;
        }
        sig.copy_from_slice(signature);
        vk.verify(data, &Signature::from_bytes(&sig)).is_ok()
    }
}

/// Where key material lives. The default file store writes a compact
/// hex-encoded file; production hosts should supply a platform keystore.
pub trait IdentityStore: Send + Sync {
    fn save(&self, identity: &CypherIdentity) -> std::io::Result<()>;
    fn load(&self) -> std::io::Result<Option<CypherIdentity>>;
    fn wipe(&self) -> std::io::Result<()>;
}

/// Simple file-backed store (JSON, 0600). Suitable for tests and desktop.
pub struct FileIdentityStore {
    path: std::path::PathBuf,
}

impl FileIdentityStore {
    pub fn new(path: impl Into<std::path::PathBuf>) -> Self {
        FileIdentityStore { path: path.into() }
    }
}

impl IdentityStore for FileIdentityStore {
    fn save(&self, identity: &CypherIdentity) -> std::io::Result<()> {
        let (noise, signing) = identity.to_bytes();
        let doc = serde_json::json!({
            "noiseStaticKey": hex_encode(&noise),
            "ed25519SigningKey": hex_encode(&signing),
        });
        if let Some(parent) = self.path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(&self.path, doc.to_string())?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = std::fs::set_permissions(&self.path, std::fs::Permissions::from_mode(0o600));
        }
        Ok(())
    }

    fn load(&self) -> std::io::Result<Option<CypherIdentity>> {
        let Ok(raw) = std::fs::read_to_string(&self.path) else {
            return Ok(None);
        };
        let doc: serde_json::Value = serde_json::from_str(&raw)
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e))?;
        let noise = doc["noiseStaticKey"].as_str().and_then(crate::util::hex_decode);
        let signing = doc["ed25519SigningKey"].as_str().and_then(crate::util::hex_decode);
        match (noise, signing) {
            (Some(n), Some(s)) if n.len() == 32 && s.len() == 32 => {
                let mut n_arr = [0u8; 32];
                n_arr.copy_from_slice(&n);
                let mut s_arr = [0u8; 32];
                s_arr.copy_from_slice(&s);
                Ok(Some(CypherIdentity::from_bytes(n_arr, s_arr)))
            }
            _ => Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "corrupt identity file",
            )),
        }
    }

    fn wipe(&self) -> std::io::Result<()> {
        match std::fs::remove_file(&self.path) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(e),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_round_trip_and_signing() {
        let (n, s) = CypherIdentity::generate().to_bytes();
        let id = CypherIdentity::from_bytes(n, s);
        assert_eq!(id.to_bytes(), (n, s));

        // Peer ID derivation matches the fingerprint prefix.
        let fp = id.fingerprint();
        assert_eq!(fp.len(), 64);
        assert_eq!(id.peer_id().as_str(), &fp[..16]);

        // Ed25519 sign/verify.
        let sig = id.sign(b"packet preimage");
        assert_eq!(sig.len(), 64);
        assert!(CypherIdentity::verify(&sig, b"packet preimage", &id.signing_public()));
        assert!(!CypherIdentity::verify(&sig, b"tampered", &id.signing_public()));

        // Deterministic Ed25519: same input, same signature bytes.
        assert_eq!(id.sign(b"packet preimage"), sig);
    }

    #[test]
    fn file_store_round_trip() {
        let dir = std::env::temp_dir().join(format!("cypher-test-{}", std::process::id()));
        let store = FileIdentityStore::new(dir.join("identity.json"));
        let id = CypherIdentity::generate();
        store.save(&id).unwrap();
        let loaded = store.load().unwrap().expect("present");
        assert_eq!(loaded.fingerprint(), id.fingerprint());
        assert_eq!(loaded.to_bytes(), id.to_bytes());
        store.wipe().unwrap();
        assert!(store.load().unwrap().is_none());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_vector_signing_seed() {
        // The courier test-vector signing seed 0x42*32 must derive the
        // documented Ed25519 public key.
        let seed = [0x42u8; 32];
        let sk = SigningKey::from_bytes(&seed);
        assert_eq!(
            hex_encode(&sk.verifying_key().to_bytes()),
            "2152f8d19b791d24453242e15f2eab6cb7cffa7b6a5ed30097960e069881db12"
        );
    }
}
