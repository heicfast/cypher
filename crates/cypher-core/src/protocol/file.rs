//! File transfer packets and private-media stable identities.
//!
//! `BitchatFilePacket` TLV: `0x01 fileName` (u16 BE len),
//! `0x02 fileSize` (`[u16 len=4][u32 BE]` canonical; legacy `[u16 len=8][u64
//! BE]` accepted on read), `0x03 mimeType` (u16 BE len),
//! `0x04 content` — **canonical v2: `[tag][u32 BE length][bytes]`** with a
//! legacy `[tag][u16 BE length][bytes]` fallback on read; content from
//! multiple `0x04` TLVs is concatenated.

use crate::consts;
use crate::tlv::TlvReader;
use crate::util::be;

pub const MAX_PAYLOAD_BYTES: usize = consts::FILE_MAX_PAYLOAD_BYTES;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BitchatFilePacket {
    pub file_name: String,
    pub file_size: u32,
    pub mime_type: String,
    pub content: Vec<u8>,
}

impl BitchatFilePacket {
    pub fn new(file_name: impl Into<String>, mime_type: impl Into<String>, content: Vec<u8>) -> Self {
        let size = content.len() as u32;
        BitchatFilePacket {
            file_name: file_name.into(),
            file_size: size,
            mime_type: mime_type.into(),
            content,
        }
    }

    pub fn encode(&self) -> Option<Vec<u8>> {
        if self.content.is_empty() || self.content.len() > MAX_PAYLOAD_BYTES {
            return None;
        }
        let resolved_size = self.file_size.min(self.content.len() as u32);
        let name = self.file_name.as_bytes();
        let mime = self.mime_type.as_bytes();
        if name.len() > u16::MAX as usize || mime.len() > u16::MAX as usize {
            return None;
        }

        let mut encoded = Vec::with_capacity(6 + name.len() + 6 + 4 + 6 + mime.len() + 5 + self.content.len());

        encoded.push(0x01);
        be::put_u16(&mut encoded, name.len() as u16);
        encoded.extend_from_slice(name);

        encoded.push(0x02);
        be::put_u16(&mut encoded, 4);
        be::put_u32(&mut encoded, resolved_size);

        encoded.push(0x03);
        be::put_u16(&mut encoded, mime.len() as u16);
        encoded.extend_from_slice(mime);

        // Canonical v2: 4-byte content length.
        encoded.push(0x04);
        be::put_u32(&mut encoded, self.content.len() as u32);
        encoded.extend_from_slice(&self.content);

        Some(encoded)
    }

    pub fn decode(data: &[u8]) -> Option<Self> {
        let mut file_name: Option<String> = None;
        let mut file_size: Option<u32> = None;
        let mut mime_type: Option<String> = None;
        let mut content: Vec<u8> = Vec::new();

        let mut i = 0usize;
        let end = data.len();
        while i < end {
            let tag = data[i];
            i += 1;

            let mut length: Option<usize> = None;
            if tag == 0x04 {
                // Canonical: u32 BE length; legacy fallback: u16 BE.
                if end - i >= 4 {
                    let canonical = u32::from_be_bytes(data[i..i + 4].try_into().ok()?) as usize;
                    if canonical <= end - i - 4 {
                        length = Some(canonical);
                        i += 4;
                    }
                }
                if length.is_none() && end - i >= 2 {
                    length = Some(u16::from_be_bytes(data[i..i + 2].try_into().ok()?) as usize);
                    i += 2;
                }
            } else if end - i >= 2 {
                length = Some(u16::from_be_bytes(data[i..i + 2].try_into().ok()?) as usize);
                i += 2;
            }
            let tlv_length = length?;

            if end - i < tlv_length {
                return None;
            }
            let value = &data[i..i + tlv_length];
            i += tlv_length;

            match tag {
                0x01 => file_name = String::from_utf8(value.to_vec()).ok(),
                0x02 => {
                    // Canonical len=4 (u32 BE); legacy len=8 (u64 BE).
                    let size = match value.len() {
                        4 => u32::from_be_bytes(value.try_into().ok()?),
                        8 => {
                            let v = u64::from_be_bytes(value.try_into().ok()?);
                            u32::try_from(v).ok()?
                        }
                        _ => return None,
                    };
                    if size as usize > MAX_PAYLOAD_BYTES {
                        return None;
                    }
                    file_size = Some(size);
                }
                0x03 => mime_type = String::from_utf8(value.to_vec()).ok(),
                0x04 => content.extend_from_slice(value),
                _ => {} // skip unknown
            }
        }

        if content.is_empty() || content.len() > MAX_PAYLOAD_BYTES {
            return None;
        }
        Some(BitchatFilePacket {
            file_name: file_name?,
            file_size: file_size.unwrap_or(content.len() as u32),
            mime_type: mime_type?,
            content,
        })
    }

    /// Category prefix used in message content (`[image] `, `[voice] `,
    /// `[file] `) per bitchat's `MimeType.Category`.
    pub fn message_prefix(&self) -> &'static str {
        if self.mime_type.starts_with("image/") {
            "[image] "
        } else if self.mime_type.starts_with("audio/") {
            "[voice] "
        } else {
            "[file] "
        }
    }
}

/// Stable message identity for private media — the receipt key derived on
/// both ends. Only `img_<uuid>.jpg|jpeg` and `voice_<16-hex>.m4a` leaf names
/// (with a valid UUID or burst entropy token) qualify.
pub struct PrivateMediaMessageIdentity;

impl PrivateMediaMessageIdentity {
    const DOMAIN: &[u8] = b"bitchat-private-media-message-v1";
    const ID_PREFIX: &str = "media-";
    const DIGEST_HEX_LENGTH: usize = 32;

    /// `media-…` shape check.
    pub fn is_stable_id(candidate: &str) -> bool {
        let Some(digest) = candidate.strip_prefix(Self::ID_PREFIX) else {
            return false;
        };
        digest.len() == Self::DIGEST_HEX_LENGTH
            && digest.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    }

    fn is_uuid(s: &str) -> bool {
        let b = s.as_bytes();
        if b.len() != 36 {
            return false;
        }
        for (i, &c) in b.iter().enumerate() {
            match i {
                8 | 13 | 18 | 23 => {
                    if c != b'-' {
                        return false;
                    }
                }
                _ => {
                    if !(c.is_ascii_hexdigit()) {
                        return false;
                    }
                }
            }
        }
        true
    }

    pub fn stable_id(sender_short: &str, recipient_short: &str, file_name: &str) -> Option<String> {
        if file_name.is_empty() {
            return None;
        }
        // Must be a leaf name (no path components).
        let leaf = file_name.rsplit('/').next()?;
        if leaf != file_name {
            return None;
        }
        // Split stem + extension (last dot).
        let (stem, ext) = match leaf.rsplit_once('.') {
            Some((s, e)) => (s, e.to_lowercase()),
            None => (leaf, String::new()),
        };
        if stem.starts_with("img_") {
            if ext != "jpg" && ext != "jpeg" {
                return None;
            }
        } else if stem.starts_with("voice_") {
            if ext != "m4a" {
                return None;
            }
        } else {
            return None;
        }
        // Entropy: UUID token, or a 16-hex burst id for voice names.
        let entropy_token = stem.rsplit('_').next();
        let has_uuid_entropy = entropy_token.map(Self::is_uuid).unwrap_or(false);
        let burst = stem.strip_prefix("voice_").unwrap_or("");
        let has_burst_entropy =
            !burst.is_empty() && burst.len() == 16 && burst.bytes().all(|b| b.is_ascii_hexdigit());
        if !has_uuid_entropy && !has_burst_entropy {
            return None;
        }

        // Fields are the UTF-8 bytes of the SHORT BARE peer-ID strings and
        // the leaf name, each length-prefixed with u32 BE.
        let sender_bare = bare_short(sender_short);
        let recipient_bare = bare_short(recipient_short);
        let fields: [&[u8]; 3] = [sender_bare.as_bytes(), recipient_bare.as_bytes(), leaf.as_bytes()];

        let mut input = Self::DOMAIN.to_vec();
        for field in fields {
            be::put_u32(&mut input, field.len() as u32);
            input.extend_from_slice(field);
        }
        let digest = crate::util::sha256(&input);
        let hex = crate::util::hex_encode(&digest);
        Some(format!("{}{}", Self::ID_PREFIX, &hex[..Self::DIGEST_HEX_LENGTH]))
    }
}

/// The short bare form of a peer ID (lowercase, prefix stripped, 16 hex).
fn bare_short(peer: &str) -> String {
    let lowered = peer.to_lowercase();
    let bare = lowered
        .strip_prefix("mesh:")
        .or_else(|| lowered.strip_prefix("name:"))
        .or_else(|| lowered.strip_prefix("noise:"))
        .or_else(|| lowered.strip_prefix("bridge:"))
        .unwrap_or(&lowered);
    bare.chars().take(16).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn file_packet_round_trip() {
        let pkt = BitchatFilePacket::new("photo.jpg", "image/jpeg", vec![0xAB; 2048]);
        let encoded = pkt.encode().unwrap();
        let decoded = BitchatFilePacket::decode(&encoded).unwrap();
        assert_eq!(decoded.file_name, "photo.jpg");
        assert_eq!(decoded.file_size, 2048);
        assert_eq!(decoded.mime_type, "image/jpeg");
        assert_eq!(decoded.content.len(), 2048);
        assert_eq!(decoded.message_prefix(), "[image] ");
        // Canonical encoding: content TLV carries a u32 length.
        assert_eq!(&encoded[encoded.len() - 2048 - 5..encoded.len() - 2048], &[0x04, 0, 0, 8, 0]);
    }

    #[test]
    fn legacy_u16_content_length_accepted() {
        // Hand-craft a legacy [tag][u16 len][content] TLV.
        let mut data = Vec::new();
        data.push(0x01);
        be::put_u16(&mut data, 4);
        data.extend_from_slice(b"bins");
        data.push(0x03);
        be::put_u16(&mut data, 24);
        data.extend_from_slice(b"application/octet-stream");
        data.push(0x04);
        be::put_u16(&mut data, 100);
        data.extend_from_slice(&[0x42; 100]);
        let decoded = BitchatFilePacket::decode(&data).unwrap();
        assert_eq!(decoded.content.len(), 100);
        assert_eq!(decoded.file_size, 100);
    }

    #[test]
    fn legacy_u64_file_size_accepted() {
        // Hand-craft a len=8 (u64 BE) fileSize TLV alongside canonical content.
        let mut data = Vec::new();
        data.push(0x01);
        be::put_u16(&mut data, 7);
        data.extend_from_slice(b"old.bin");
        data.push(0x02);
        be::put_u16(&mut data, 8);
        be::put_u64(&mut data, 100);
        data.push(0x03);
        be::put_u16(&mut data, 24);
        data.extend_from_slice(b"application/octet-stream");
        data.push(0x04);
        be::put_u32(&mut data, 100);
        data.extend_from_slice(&[0x42; 100]);
        let decoded = BitchatFilePacket::decode(&data).unwrap();
        assert_eq!(decoded.file_size, 100);
        assert_eq!(decoded.content.len(), 100);
    }

    #[test]
    fn oversized_rejected() {
        assert!(BitchatFilePacket::new("big.bin", "application/octet-stream", vec![0; MAX_PAYLOAD_BYTES + 1]).encode().is_none());
    }

    #[test]
    fn golden_private_media_stable_id() {
        // From bitchatTests BitchatFilePacketTests (cross-platform vector).
        assert_eq!(
            PrivateMediaMessageIdentity::stable_id(
                "0011223344556677",
                "8899aabbccddeeff",
                "img_20260725_105708_1CC2760D-76AA-40C3-8013-C7FAA6C2EF99.jpg"
            )
            .as_deref(),
            Some("media-910bd42c65060ab76bb6406f220c4516")
        );
        // Converges across peer-ID alias forms.
        assert_eq!(
            PrivateMediaMessageIdentity::stable_id(
                "mesh:0011223344556677",
                "8899aabbccddeeff",
                "img_20260725_105708_1CC2760D-76AA-40C3-8013-C7FAA6C2EF99.jpg"
            ),
            PrivateMediaMessageIdentity::stable_id(
                "0011223344556677",
                "8899aabbccddeeff",
                "img_20260725_105708_1CC2760D-76AA-40C3-8013-C7FAA6C2EF99.jpg"
            )
        );
        // Voice burst id qualifies.
        assert!(PrivateMediaMessageIdentity::stable_id("aabb001122334455", "ccdd001122334455", "voice_deadbeefdeadbeef.m4a").is_some());
        // Non-qualifying filenames → None.
        assert!(PrivateMediaMessageIdentity::stable_id("a", "b", "notes.txt").is_none());
        assert!(PrivateMediaMessageIdentity::stable_id("a", "b", "img_../evil.jpg").is_none());
        assert!(PrivateMediaMessageIdentity::stable_id("a", "b", "img_nouuid.jpg").is_none());
        assert!(PrivateMediaMessageIdentity::stable_id("a", "b", "voice_short.m4a").is_none());
        // Stable-ID shape check.
        assert!(PrivateMediaMessageIdentity::is_stable_id("media-910bd42c65060ab76bb6406f220c4516"));
        assert!(!PrivateMediaMessageIdentity::is_stable_id("media-ZZZ"));
        assert!(!PrivateMediaMessageIdentity::is_stable_id("img-910bd42c65060ab76bb6406f220c4516"));
    }
}
