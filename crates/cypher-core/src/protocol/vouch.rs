//! Transitive verification vouches (Noise payload 0x12).
//!
//! Entry TLV (1-byte lengths): `0x01 voucheeFingerprint(32)`,
//! `0x02 voucheeSigningKey(32)`, `0x03 timestampMs(8 BE)`,
//! `0x04 signature(64)`. Signable bytes: `"bitchat-vouch-v1" ‖ fingerprint ‖
//! signingKey ‖ u64_be(timestampMs)`. Batch: `[u8 count]` then per entry
//! `[u16 BE len][entry TLV]`, max 16.

use crate::tlv::{write_short, TlvReader};
use crate::util::be;

pub const VOUCH_SIGNING_DOMAIN: &[u8] = b"bitchat-vouch-v1";
pub const FINGERPRINT_LENGTH: usize = 32;
pub const SIGNING_KEY_LENGTH: usize = 32;
pub const SIGNATURE_LENGTH: usize = 64;
pub const MAX_BATCH: usize = 16;
pub const MAX_AGE_MS: u64 = 30 * 24 * 3600 * 1000;
pub const MAX_CLOCK_SKEW_MS: u64 = 3600 * 1000;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct VouchAttestation {
    /// Raw 32-byte SHA-256 of the vouchee's Noise static key.
    pub vouchee_fingerprint: Vec<u8>,
    pub vouchee_signing_key: Vec<u8>,
    pub timestamp_ms: u64,
    /// Ed25519 signature by the voucher.
    pub signature: Vec<u8>,
}

impl VouchAttestation {
    pub fn signable_bytes(&self) -> Vec<u8> {
        let mut data = VOUCH_SIGNING_DOMAIN.to_vec();
        data.extend_from_slice(&self.vouchee_fingerprint);
        data.extend_from_slice(&self.vouchee_signing_key);
        be::put_u64(&mut data, self.timestamp_ms);
        data
    }

    pub fn encode(&self) -> Option<Vec<u8>> {
        if self.vouchee_fingerprint.len() != FINGERPRINT_LENGTH
            || self.vouchee_signing_key.len() != SIGNING_KEY_LENGTH
            || self.signature.len() != SIGNATURE_LENGTH
        {
            return None;
        }
        let mut out = Vec::new();
        write_short(&mut out, 0x01, &self.vouchee_fingerprint);
        write_short(&mut out, 0x02, &self.vouchee_signing_key);
        write_short(&mut out, 0x03, &self.timestamp_ms.to_be_bytes());
        write_short(&mut out, 0x04, &self.signature);
        Some(out)
    }

    pub fn decode(data: &[u8]) -> Option<Self> {
        let mut fingerprint: Option<Vec<u8>> = None;
        let mut signing_key: Option<Vec<u8>> = None;
        let mut timestamp_ms: Option<u64> = None;
        let mut signature: Option<Vec<u8>> = None;
        let mut reader = TlvReader::short(data);
        while let Some((tag, value)) = reader.next_record() {
            match tag {
                0x01 if value.len() == FINGERPRINT_LENGTH => fingerprint = Some(value.to_vec()),
                0x02 if value.len() == SIGNING_KEY_LENGTH => signing_key = Some(value.to_vec()),
                0x03 if value.len() == 8 => timestamp_ms = Some(u64::from_be_bytes(value.try_into().ok()?)),
                0x04 if value.len() == SIGNATURE_LENGTH => signature = Some(value.to_vec()),
                _ => {} // skip unknown
            }
        }
        Some(VouchAttestation {
            vouchee_fingerprint: fingerprint?,
            vouchee_signing_key: signing_key?,
            timestamp_ms: timestamp_ms?,
            signature: signature?,
        })
    }

    /// Verify the voucher's Ed25519 signature.
    pub fn verify_signature(&self, voucher_signing_key: &[u8; 32]) -> bool {
        crate::identity::CypherIdentity::verify(&self.signature, &self.signable_bytes(), voucher_signing_key)
    }

    /// Freshness: within the 30-day window with 1 h clock-skew tolerance.
    pub fn is_fresh(&self, now_ms: u64) -> bool {
        // Future timestamps beyond skew are invalid; past beyond window too.
        if self.timestamp_ms > now_ms + MAX_CLOCK_SKEW_MS {
            return false;
        }
        let age = now_ms.saturating_sub(self.timestamp_ms);
        age <= MAX_AGE_MS
    }
}

/// Encode a batch of attestations (capped at 16; the declared count is
/// untrusted on the receiving side).
pub fn encode_batch(attestations: &[VouchAttestation]) -> Option<Vec<u8>> {
    if attestations.len() > MAX_BATCH {
        return None;
    }
    let mut out = Vec::new();
    out.push(attestations.len() as u8);
    for attestation in attestations {
        let entry = attestation.encode()?;
        be::put_u16(&mut out, entry.len() as u16);
        out.extend_from_slice(&entry);
    }
    Some(out)
}

/// Decode a batch; malformed entries are skipped, at most `MAX_BATCH` kept.
pub fn decode_batch(data: &[u8]) -> Vec<VouchAttestation> {
    let mut out = Vec::new();
    let Some(&count) = data.first() else {
        return out;
    };
    let count = (count as usize).min(MAX_BATCH);
    let mut off = 1usize;
    for _ in 0..count {
        if data.len() - off < 2 {
            break;
        }
        let len = u16::from_be_bytes(data[off..off + 2].try_into().unwrap()) as usize;
        off += 2;
        if data.len() - off < len {
            break;
        }
        if let Some(attestation) = VouchAttestation::decode(&data[off..off + len]) {
            out.push(attestation);
        }
        off += len;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::CypherIdentity;

    fn sample(voucher: &CypherIdentity, vouchee: &CypherIdentity) -> VouchAttestation {
        let mut attestation = VouchAttestation {
            vouchee_fingerprint: crate::util::sha256(&vouchee.noise_static_public()).to_vec(),
            vouchee_signing_key: vouchee.signing_public().to_vec(),
            timestamp_ms: crate::util::now_ms(),
            signature: vec![0; 64],
        };
        attestation.signature = voucher.sign(&attestation.signable_bytes()).to_vec();
        attestation
    }

    #[test]
    fn round_trip_and_verify() {
        let voucher = CypherIdentity::generate();
        let vouchee = CypherIdentity::generate();
        let attestation = sample(&voucher, &vouchee);
        let encoded = attestation.encode().unwrap();
        let decoded = VouchAttestation::decode(&encoded).unwrap();
        assert_eq!(decoded, attestation);
        assert!(decoded.verify_signature(&voucher.signing_public()));
        assert!(!decoded.verify_signature(&vouchee.signing_public()));
        assert!(decoded.is_fresh(crate::util::now_ms()));
    }

    #[test]
    fn tampered_fails() {
        let voucher = CypherIdentity::generate();
        let vouchee = CypherIdentity::generate();
        let mut attestation = sample(&voucher, &vouchee);
        attestation.timestamp_ms += 1;
        assert!(!attestation.verify_signature(&voucher.signing_public()));
    }

    #[test]
    fn batch_round_trip_and_caps() {
        let voucher = CypherIdentity::generate();
        let batch: Vec<VouchAttestation> = (0..5)
            .map(|_| {
                let vouchee = CypherIdentity::generate();
                sample(&voucher, &vouchee)
            })
            .collect();
        let encoded = encode_batch(&batch).unwrap();
        let decoded = decode_batch(&encoded);
        assert_eq!(decoded.len(), 5);
        assert!(decoded.iter().all(|a| a.verify_signature(&voucher.signing_public())));
        // Oversized batch rejected at encode.
        let too_many: Vec<VouchAttestation> = (0..17).map(|i| {
            let mut a = sample(&voucher, &voucher);
            a.timestamp_ms = i;
            a
        }).collect();
        assert!(encode_batch(&too_many).is_none());
        // Malformed entries skipped on decode.
        let mut bad = encoded.clone();
        bad.extend_from_slice(&[0u8, 0xFF]); // dangling length
        assert_eq!(decode_batch(&bad).len(), 5);
    }

    #[test]
    fn freshness_window() {
        let voucher = CypherIdentity::generate();
        let mut attestation = sample(&voucher, &voucher);
        let now = crate::util::now_ms();
        attestation.timestamp_ms = now - MAX_AGE_MS - 2 * MAX_CLOCK_SKEW_MS;
        assert!(!attestation.is_fresh(now));
        attestation.timestamp_ms = now + 2 * MAX_CLOCK_SKEW_MS;
        assert!(!attestation.is_fresh(now));
        attestation.timestamp_ms = now + MAX_CLOCK_SKEW_MS / 2;
        assert!(attestation.is_fresh(now));
    }
}
