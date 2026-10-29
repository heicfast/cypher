//! Courier envelopes — opaque store-and-forward mail carried by peers.
//!
//! TLV with 2-byte BE lengths: `0x01 recipientTag(16)`, `0x02 expiry(u64 BE
//! ms)`, `0x03 ciphertext(1..=16 KiB)`, `0x04 copies(1, omitted when 1)`,
//! `0x05 prekeyID(u32 BE, omitted for v1 static seals)`.
//!
//! The recipient tag is `HMAC-SHA256(noiseStaticKey, "bitchat-courier-tag-v1"
//! ‖ u32_be(epochDay))[0..16]`; matching tests day−1/day/day+1.

use crate::consts;
use crate::tlv::{write_long, TlvReader};
use crate::util::hmac_sha256;

pub const TAG_LENGTH: usize = consts::COURIER_TAG_LENGTH;
pub const MAX_CIPHERTEXT_BYTES: usize = consts::COURIER_MAX_CIPHERTEXT_BYTES;
pub const MAX_COPIES: u8 = consts::COURIER_MAX_COPIES;
pub const TAG_CONTEXT: &str = consts::COURIER_TAG_CONTEXT;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CourierEnvelope {
    /// Rotating recipient hint (16 bytes).
    pub recipient_tag: Vec<u8>,
    /// Milliseconds since epoch after which the envelope is discarded.
    pub expiry: u64,
    /// Opaque one-way Noise X ciphertext.
    pub ciphertext: Vec<u8>,
    /// Spray-and-wait copy budget (1 = carry-only), clamped to 1..=8.
    pub copies: u8,
    /// `None` = v1 (sealed to the static key); `Some(id)` = v2 (sealed to a
    /// one-time prekey, forward secret).
    pub prekey_id: Option<u32>,
}

impl CourierEnvelope {
    pub fn new(
        recipient_tag: Vec<u8>,
        expiry: u64,
        ciphertext: Vec<u8>,
        copies: u8,
        prekey_id: Option<u32>,
    ) -> Self {
        CourierEnvelope {
            recipient_tag,
            expiry,
            ciphertext,
            copies: copies.clamp(1, MAX_COPIES),
            prekey_id,
        }
    }

    /// The same envelope with a different remaining copy budget.
    pub fn with_copies(&self, copies: u8) -> Self {
        CourierEnvelope {
            recipient_tag: self.recipient_tag.clone(),
            expiry: self.expiry,
            ciphertext: self.ciphertext.clone(),
            copies: copies.clamp(1, MAX_COPIES),
            prekey_id: self.prekey_id,
        }
    }

    pub fn is_expired(&self, now_ms: u64) -> bool {
        now_ms >= self.expiry
    }

    pub fn encode(&self) -> Option<Vec<u8>> {
        if self.recipient_tag.len() != TAG_LENGTH {
            return None;
        }
        if self.ciphertext.is_empty() || self.ciphertext.len() > MAX_CIPHERTEXT_BYTES {
            return None;
        }
        let mut data = Vec::with_capacity(3 * 3 + TAG_LENGTH + 8 + self.ciphertext.len());
        write_long(&mut data, 0x01, &self.recipient_tag);
        write_long(&mut data, 0x02, &self.expiry.to_be_bytes());
        write_long(&mut data, 0x03, &self.ciphertext);
        // Omitted when 1: byte-identical to the pre-spray wire format.
        if self.copies > 1 {
            write_long(&mut data, 0x04, &[self.copies]);
        }
        // Omitted for v1 static-sealed envelopes.
        if let Some(id) = self.prekey_id {
            write_long(&mut data, 0x05, &id.to_be_bytes());
        }
        Some(data)
    }

    pub fn decode(data: &[u8]) -> Option<Self> {
        let mut recipient_tag: Option<Vec<u8>> = None;
        let mut expiry: Option<u64> = None;
        let mut ciphertext: Option<Vec<u8>> = None;
        let mut copies: u8 = 1;
        let mut prekey_id: Option<u32> = None;

        let mut reader = TlvReader::long(data);
        while let Some((tag, value)) = reader.next_record() {
            match tag {
                0x01 => {
                    if value.len() != TAG_LENGTH {
                        return None;
                    }
                    recipient_tag = Some(value.to_vec());
                }
                0x02 => {
                    if value.len() != 8 {
                        return None;
                    }
                    expiry = Some(u64::from_be_bytes(value.try_into().ok()?));
                }
                0x03 => {
                    if value.is_empty() || value.len() > MAX_CIPHERTEXT_BYTES {
                        return None;
                    }
                    ciphertext = Some(value.to_vec());
                }
                0x04 => {
                    if value.len() != 1 {
                        return None;
                    }
                    copies = value[0];
                }
                0x05 => {
                    if value.len() != 4 {
                        return None;
                    }
                    prekey_id = Some(u32::from_be_bytes(value.try_into().ok()?));
                }
                _ => {} // skip unknown
            }
        }

        Some(CourierEnvelope::new(
            recipient_tag?,
            expiry?,
            ciphertext?,
            copies,
            prekey_id,
        ))
    }

    // ---- recipient tags -----------------------------------------------------

    /// UTC day number used to rotate recipient tags.
    pub fn epoch_day(unix_seconds: u64) -> u32 {
        (unix_seconds / 86_400) as u32
    }

    /// Rotating recipient hint for a given day; computable only by parties
    /// who know the recipient's Noise static public key.
    pub fn recipient_tag(noise_static_key: &[u8], epoch_day: u32) -> [u8; TAG_LENGTH] {
        let mut message = TAG_CONTEXT.as_bytes().to_vec();
        message.extend_from_slice(&epoch_day.to_be_bytes());
        let mac = hmac_sha256(noise_static_key, &message);
        let mut tag = [0u8; TAG_LENGTH];
        tag.copy_from_slice(&mac[..TAG_LENGTH]);
        tag
    }

    /// Tags to test when checking whether an envelope is addressed to us:
    /// day−1, day, day+1 (midnight / clock-skew tolerance).
    pub fn candidate_tags(noise_static_key: &[u8], unix_seconds: u64) -> Vec<[u8; TAG_LENGTH]> {
        let day = Self::epoch_day(unix_seconds);
        [day.saturating_sub(1), day, day + 1]
            .into_iter()
            .map(|d| Self::recipient_tag(noise_static_key, d))
            .collect()
    }

    /// Does this envelope match any of the given candidate tags?
    pub fn matches_any(&self, candidates: &[[u8; TAG_LENGTH]]) -> bool {
        candidates
            .iter()
            .any(|t| t.as_slice() == self.rec_tag_slice())
    }

    fn rec_tag_slice(&self) -> &[u8] {
        &self.recipient_tag
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::util::hex_decode;

    /// Fields from bitchat's docs/courier-test-vectors.json.
    fn vector_inputs() -> (Vec<u8>, Vec<u8>, u64, u32) {
        (
            hex_decode("000102030405060708090a0b0c0d0e0f").unwrap(),
            hex_decode("636f75726965722d766563746f722d636970686572746578742d30303031").unwrap(),
            1_800_000_000_000,
            20_833,
        )
    }

    #[test]
    fn golden_envelope_encoding() {
        let (tag, ciphertext, expiry, _) = vector_inputs();
        let envelope = CourierEnvelope::new(tag, expiry, ciphertext, 4, Some(0x11223344));
        let encoded = envelope.encode().unwrap();
        assert_eq!(encoded.len(), 74);
        assert_eq!(
            crate::util::hex_encode(&encoded),
            "010010000102030405060708090a0b0c0d0e0f020008000001a3185c500003001e636f75726965722d766563746f722d636970686572746578742d303030310400010405000411223344"
        );
        // Round trip.
        let decoded = CourierEnvelope::decode(&encoded).unwrap();
        assert_eq!(decoded, envelope);
        assert_eq!(decoded.copies, 4);
        assert_eq!(decoded.prekey_id, Some(0x11223344));
    }

    #[test]
    fn copies_omitted_when_one_is_byte_identical_legacy() {
        let (tag, ciphertext, expiry, _) = vector_inputs();
        let v1 = CourierEnvelope::new(tag.clone(), expiry, ciphertext.clone(), 1, None);
        let encoded = v1.encode().unwrap();
        assert_eq!(
            crate::util::hex_encode(&encoded),
            "010010000102030405060708090a0b0c0d0e0f020008000001a3185c500003001e636f75726965722d766563746f722d636970686572746578742d30303031"
        );
        // No copies / prekeyID TLVs at all.
        assert!(!encoded.windows(2).any(|w| w == [0x04, 0x00]));
    }

    #[test]
    fn copies_clamped_never_rejected() {
        let (tag, ciphertext, expiry, _) = vector_inputs();
        assert_eq!(
            CourierEnvelope::new(tag.clone(), expiry, ciphertext.clone(), 0, None).copies,
            1
        );
        assert_eq!(
            CourierEnvelope::new(tag.clone(), expiry, ciphertext.clone(), 4, None).copies,
            4
        );
        assert_eq!(
            CourierEnvelope::new(tag, expiry, ciphertext, 200, None).copies,
            8
        );
    }

    #[test]
    fn golden_recipient_tag() {
        let (_, _, _, epoch_day) = vector_inputs();
        let key =
            hex_decode("a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf").unwrap();
        let tag = CourierEnvelope::recipient_tag(&key, epoch_day);
        assert_eq!(
            crate::util::hex_encode(&tag),
            "ad8514c90ca1fa6bf44e38c8a6252482"
        );
    }

    #[test]
    fn candidate_tags_cover_three_days() {
        let key = [7u8; 32];
        let tags = CourierEnvelope::candidate_tags(&key, 86_400 * 100 + 10);
        assert_eq!(tags.len(), 3);
        assert_eq!(tags[0], CourierEnvelope::recipient_tag(&key, 99));
        assert_eq!(tags[1], CourierEnvelope::recipient_tag(&key, 100));
        assert_eq!(tags[2], CourierEnvelope::recipient_tag(&key, 101));
        // Day 0 does not underflow.
        let early = CourierEnvelope::candidate_tags(&key, 5);
        assert_eq!(early[0], CourierEnvelope::recipient_tag(&key, 0));
    }

    #[test]
    fn decode_rejects_bad_shapes() {
        // Missing fields.
        assert!(CourierEnvelope::decode(&[]).is_none());
        // 8-byte tag.
        let mut bad = vec![0x01, 0x00, 0x08];
        bad.extend_from_slice(&[0u8; 8]);
        assert!(CourierEnvelope::decode(&bad).is_none());
        // Empty ciphertext.
        let (tag, _, expiry, _) = vector_inputs();
        let mut empty_ct = vec![0x01, 0x00, 0x10];
        empty_ct.extend_from_slice(&tag);
        empty_ct.extend_from_slice(&[0x02, 0x00, 0x08]);
        empty_ct.extend_from_slice(&expiry.to_be_bytes());
        empty_ct.extend_from_slice(&[0x03, 0x00, 0x00]);
        assert!(CourierEnvelope::decode(&empty_ct).is_none());
        // Unknown TLV skipped.
        let (tag, ciphertext, expiry, _) = vector_inputs();
        let good = CourierEnvelope::new(tag, expiry, ciphertext, 1, None);
        let mut with_unknown = good.encode().unwrap();
        write_long(&mut with_unknown, 0x7F, b"future");
        assert!(CourierEnvelope::decode(&with_unknown).is_some());
    }

    #[test]
    fn expiry_semantics() {
        let (tag, ciphertext, _, _) = vector_inputs();
        let e = CourierEnvelope::new(tag, 1000, ciphertext, 1, None);
        assert!(e.is_expired(1000));
        assert!(!e.is_expired(999));
    }
}
