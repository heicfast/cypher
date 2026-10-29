//! One-time prekey bundles — forward secrecy for store-and-forward mail.
//!
//! TLV with 2-byte BE lengths: `0x01 noiseStaticPublicKey(32)`,
//! `0x02 prekeys ([u32 BE id ‖ 32 B key] × n, 1..=8, no duplicate IDs)`,
//! `0x03 generatedAt(u64 BE ms)`, `0x04 signature(64)`.

use serde::{Deserialize, Serialize};

use crate::consts;
use crate::tlv::{write_long, TlvReader};
use crate::util::be;

pub const KEY_LENGTH: usize = 32;
pub const SIGNATURE_LENGTH: usize = 64;
pub const MAX_PREKEYS: usize = consts::PREKEY_BATCH_SIZE;
const PREKEY_ENTRY_LENGTH: usize = 4 + KEY_LENGTH;
const SIGNING_CONTEXT: &[u8] = b"bitchat-prekey-bundle-v1";

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Prekey {
    pub id: u32,
    /// X25519 public key (32 bytes).
    pub public_key: Vec<u8>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PrekeyBundle {
    /// Owner's Noise static public key (32 bytes).
    pub noise_static_public_key: Vec<u8>,
    pub prekeys: Vec<Prekey>,
    /// Milliseconds since epoch; newer bundles replace older ones.
    pub generated_at: u64,
    /// Ed25519 signature over [`PrekeyBundle::signable_bytes`].
    pub signature: Vec<u8>,
}

/// Zero-pad (or truncate) a key to exactly 32 bytes for signing.
fn padded_key(key: &[u8]) -> [u8; KEY_LENGTH] {
    let mut out = [0u8; KEY_LENGTH];
    let n = key.len().min(KEY_LENGTH);
    out[..n].copy_from_slice(&key[..n]);
    out
}

impl PrekeyBundle {
    /// Canonical bytes covered by the Ed25519 signature: length-prefixed
    /// domain context, owner key, prekey count, each (id, key) pair, and the
    /// generation time.
    pub fn signable_bytes(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(
            1 + SIGNING_CONTEXT.len()
                + KEY_LENGTH
                + 1
                + self.prekeys.len() * PREKEY_ENTRY_LENGTH
                + 8,
        );
        out.push(SIGNING_CONTEXT.len().min(255) as u8);
        out.extend_from_slice(&SIGNING_CONTEXT[..255.min(SIGNING_CONTEXT.len())]);
        out.extend_from_slice(&padded_key(&self.noise_static_public_key));
        out.push(self.prekeys.len().min(255) as u8);
        for prekey in self.prekeys.iter().take(255) {
            be::put_u32(&mut out, prekey.id);
            out.extend_from_slice(&padded_key(&prekey.public_key));
        }
        be::put_u64(&mut out, self.generated_at);
        out
    }

    pub fn encode(&self) -> Option<Vec<u8>> {
        if self.noise_static_public_key.len() != KEY_LENGTH
            || self.signature.len() != SIGNATURE_LENGTH
            || self.prekeys.is_empty()
            || self.prekeys.len() > MAX_PREKEYS
            || self
                .prekeys
                .iter()
                .any(|p| p.public_key.len() != KEY_LENGTH)
        {
            return None;
        }
        let mut entries = Vec::with_capacity(self.prekeys.len() * PREKEY_ENTRY_LENGTH);
        for prekey in &self.prekeys {
            be::put_u32(&mut entries, prekey.id);
            entries.extend_from_slice(&prekey.public_key);
        }
        let mut encoded =
            Vec::with_capacity(4 * 3 + KEY_LENGTH + entries.len() + 8 + SIGNATURE_LENGTH);
        write_long(&mut encoded, 0x01, &self.noise_static_public_key);
        write_long(&mut encoded, 0x02, &entries);
        write_long(&mut encoded, 0x03, &self.generated_at.to_be_bytes());
        write_long(&mut encoded, 0x04, &self.signature);
        Some(encoded)
    }

    pub fn decode(data: &[u8]) -> Option<Self> {
        let mut noise_static_public_key: Option<Vec<u8>> = None;
        let mut prekeys: Option<Vec<Prekey>> = None;
        let mut generated_at: Option<u64> = None;
        let mut signature: Option<Vec<u8>> = None;

        let mut reader = TlvReader::long(data);
        while let Some((tag, value)) = reader.next_record() {
            match tag {
                0x01 => {
                    if value.len() != KEY_LENGTH {
                        return None;
                    }
                    noise_static_public_key = Some(value.to_vec());
                }
                0x02 => {
                    if value.is_empty()
                        || !value.len().is_multiple_of(PREKEY_ENTRY_LENGTH)
                        || value.len() / PREKEY_ENTRY_LENGTH > MAX_PREKEYS
                    {
                        return None;
                    }
                    let mut parsed = Vec::new();
                    let (entries, _) = value.as_chunks::<PREKEY_ENTRY_LENGTH>();
                    for entry in entries {
                        let id = u32::from_be_bytes(entry[..4].try_into().ok()?);
                        parsed.push(Prekey {
                            id,
                            public_key: entry[4..].to_vec(),
                        });
                    }
                    prekeys = Some(parsed);
                }
                0x03 => {
                    if value.len() != 8 {
                        return None;
                    }
                    generated_at = Some(u64::from_be_bytes(value.try_into().ok()?));
                }
                0x04 => {
                    if value.len() != SIGNATURE_LENGTH {
                        return None;
                    }
                    signature = Some(value.to_vec());
                }
                _ => {} // skip unknown
            }
        }

        let prekeys = prekeys?;
        if prekeys.is_empty() {
            return None;
        }
        // Duplicate IDs would let one consumed ID shadow another.
        let mut ids: Vec<u32> = prekeys.iter().map(|p| p.id).collect();
        ids.sort_unstable();
        ids.dedup();
        if ids.len() != prekeys.len() {
            return None;
        }
        Some(PrekeyBundle {
            noise_static_public_key: noise_static_public_key?,
            prekeys,
            generated_at: generated_at?,
            signature: signature?,
        })
    }

    /// Verify the bundle's Ed25519 signature against the owner's signing key.
    pub fn verify_signature(&self, signing_public_key: &[u8; 32]) -> bool {
        crate::identity::CypherIdentity::verify(
            &self.signature,
            &self.signable_bytes(),
            signing_public_key,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::CypherIdentity;

    fn sample_bundle(owner: &CypherIdentity) -> PrekeyBundle {
        let mut prekeys = Vec::new();
        for i in 0..3u32 {
            let mut key = vec![0u8; 32];
            key.copy_from_slice(&crate::util::sha256(&i.to_be_bytes()));
            prekeys.push(Prekey {
                id: i,
                public_key: key,
            });
        }
        let mut bundle = PrekeyBundle {
            noise_static_public_key: owner.noise_static_public().to_vec(),
            prekeys,
            generated_at: 1_750_000_000_000,
            signature: vec![0u8; 64],
        };
        bundle.signature = owner.sign(&bundle.signable_bytes()).to_vec();
        bundle
    }

    #[test]
    fn bundle_round_trip_and_verify() {
        let owner = CypherIdentity::generate();
        let bundle = sample_bundle(&owner);
        let encoded = bundle.encode().unwrap();
        let decoded = PrekeyBundle::decode(&encoded).unwrap();
        assert_eq!(decoded, bundle);
        assert!(decoded.verify_signature(&owner.signing_public()));
    }

    #[test]
    fn forged_signature_fails() {
        let owner = CypherIdentity::generate();
        let mut bundle = sample_bundle(&owner);
        bundle.signature[0] ^= 1;
        assert!(!bundle.verify_signature(&owner.signing_public()));
        // Different owner key also fails.
        let other = CypherIdentity::generate();
        assert!(!sample_bundle(&owner).verify_signature(&other.signing_public()));
    }

    #[test]
    fn duplicate_ids_rejected_on_decode() {
        let owner = CypherIdentity::generate();
        let mut bundle = sample_bundle(&owner);
        bundle.prekeys[1].id = bundle.prekeys[0].id;
        // Hand-craft the wire form with duplicate IDs (encode does not check;
        // decode must reject).
        let mut entries = Vec::new();
        for p in &bundle.prekeys {
            be::put_u32(&mut entries, p.id);
            entries.extend_from_slice(&p.public_key);
        }
        let mut data = Vec::new();
        write_long(&mut data, 0x01, &bundle.noise_static_public_key);
        write_long(&mut data, 0x02, &entries);
        write_long(&mut data, 0x03, &bundle.generated_at.to_be_bytes());
        write_long(&mut data, 0x04, &bundle.signature);
        assert!(PrekeyBundle::decode(&data).is_none());
        // The clean bundle round-trips.
        let good = sample_bundle(&owner).encode().unwrap();
        let decoded = PrekeyBundle::decode(&good).unwrap();
        assert_eq!(decoded.prekeys.len(), 3);
    }

    #[test]
    fn signable_bytes_deterministic() {
        let owner = CypherIdentity::generate();
        let b1 = sample_bundle(&owner);
        let b2 = sample_bundle(&owner);
        assert_eq!(b1.signable_bytes(), b2.signable_bytes());
        // Context prefix is length-prefixed.
        let sb = b1.signable_bytes();
        assert_eq!(sb[0], SIGNING_CONTEXT.len() as u8);
        assert_eq!(&sb[1..1 + SIGNING_CONTEXT.len()], SIGNING_CONTEXT);
    }

    #[test]
    fn unknown_tlv_skipped() {
        let owner = CypherIdentity::generate();
        let bundle = sample_bundle(&owner);
        let mut encoded = bundle.encode().unwrap();
        write_long(&mut encoded, 0x7F, b"unknown");
        let decoded = PrekeyBundle::decode(&encoded).unwrap();
        assert_eq!(decoded.prekeys.len(), bundle.prekeys.len());
    }
}
