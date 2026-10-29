//! Bulletin board: signed, persistent public notices with expiry and
//! author-signed deletion tombstones (MessageType 0x23).
//!
//! TLV (u16 BE lengths): `0x01 kind(1)`, `0x02 postID(16)`, `0x03 geohash`,
//! `0x04 content`, `0x05 authorSigningKey(32)`, `0x06 authorNickname`,
//! `0x07 createdAt(8)`, `0x08 expiresAt(8)`, `0x09 flags(1)`,
//! `0x0A signature(64)`, `0x0B deletedAt(8)`.

use crate::consts;
use crate::tlv::{write_long, TlvReader};
use crate::util::be;

pub const SIGNING_KEY_LENGTH: usize = 32;
pub const SIGNATURE_LENGTH: usize = 64;
pub const CONTENT_MAX_BYTES: usize = consts::BOARD_MAX_CONTENT_BYTES;
pub const NICKNAME_MAX_BYTES: usize = consts::GROUP_NICKNAME_MAX_BYTES;
pub const GEOHASH_MAX_LENGTH: usize = consts::BOARD_GEOHASH_MAX_CHARS;
pub const MAX_LIFETIME_MS: u64 = consts::BOARD_MAX_LIFETIME_S * 1000;
pub const POST_SIGNING_CONTEXT: &str = "bitchat-board-v1";
pub const TOMBSTONE_SIGNING_CONTEXT: &str = "bitchat-board-del-v1";
const GEOHASH_ALPHABET: &[u8] = b"0123456789bcdefghjkmnpqrstuvwxyz";

pub const KIND_POST: u8 = 0x01;
pub const KIND_TOMBSTONE: u8 = 0x02;
pub const FLAG_URGENT: u8 = 0x01;

pub fn is_valid_geohash(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= GEOHASH_MAX_LENGTH
        && s.bytes().all(|b| GEOHASH_ALPHABET.contains(&b))
}

fn append_context(out: &mut Vec<u8>, context: &str) {
    let bytes = context.as_bytes();
    out.push(bytes.len().min(255) as u8);
    out.extend_from_slice(&bytes[..bytes.len().min(255)]);
}

fn append_length_prefixed(out: &mut Vec<u8>, value: &[u8]) {
    let len = value.len().min(u16::MAX as usize) as u16;
    be::put_u16(out, len);
    out.extend_from_slice(&value[..value.len().min(u16::MAX as usize)]);
}

/// A signed, persistent bulletin-board notice.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
pub struct BoardPostPacket {
    pub post_id: Vec<u8>,
    /// Empty string scopes the post to the mesh-local board.
    pub geohash: String,
    pub content: String,
    pub author_signing_key: Vec<u8>,
    pub author_nickname: String,
    /// Milliseconds since epoch.
    pub created_at: u64,
    pub expires_at: u64,
    pub flags: u8,
    pub signature: Vec<u8>,
}

impl BoardPostPacket {
    /// Canonical bytes covered by the Ed25519 signature.
    pub fn signing_bytes(&self) -> Vec<u8> {
        let mut out = Vec::new();
        append_context(&mut out, POST_SIGNING_CONTEXT);
        out.extend_from_slice(&self.post_id);
        append_length_prefixed(&mut out, self.geohash.as_bytes());
        append_length_prefixed(&mut out, self.content.as_bytes());
        out.extend_from_slice(&self.author_signing_key);
        append_length_prefixed(&mut out, self.author_nickname.as_bytes());
        be::put_u64(&mut out, self.created_at);
        be::put_u64(&mut out, self.expires_at);
        out.push(self.flags);
        out
    }

    pub fn verify_signature(&self) -> bool {
        let Ok(key) = self.author_signing_key.clone().try_into() else {
            return false;
        };
        crate::identity::CypherIdentity::verify(&self.signature, &self.signing_bytes(), &key)
    }
}

/// Author-signed deletion marker; retained until the deleted post's expiry.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
pub struct BoardTombstonePacket {
    pub post_id: Vec<u8>,
    pub deleted_at: u64,
    pub author_signing_key: Vec<u8>,
    pub signature: Vec<u8>,
}

impl BoardTombstonePacket {
    pub fn signing_bytes(&self) -> Vec<u8> {
        let mut out = Vec::new();
        append_context(&mut out, TOMBSTONE_SIGNING_CONTEXT);
        out.extend_from_slice(&self.post_id);
        be::put_u64(&mut out, self.deleted_at);
        out
    }

    pub fn verify_signature(&self) -> bool {
        let Ok(key) = self.author_signing_key.clone().try_into() else {
            return false;
        };
        crate::identity::CypherIdentity::verify(&self.signature, &self.signing_bytes(), &key)
    }
}

/// Decoded board payload: either a live post or a tombstone.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
pub enum BoardWire {
    Post(BoardPostPacket),
    Tombstone(BoardTombstonePacket),
}

impl BoardWire {
    pub fn encode(&self) -> Option<Vec<u8>> {
        let mut out = Vec::new();
        match self {
            BoardWire::Post(post) => {
                if post.content.is_empty()
                    || post.content.len() > CONTENT_MAX_BYTES
                    || post.author_nickname.len() > NICKNAME_MAX_BYTES
                    || post.post_id.len() != 16
                    || post.author_signing_key.len() != SIGNING_KEY_LENGTH
                    || post.signature.len() != SIGNATURE_LENGTH
                    || !is_valid_geohash_or_empty(&post.geohash)
                    || post.expires_at <= post.created_at
                    || post.expires_at > post.created_at + MAX_LIFETIME_MS
                {
                    return None;
                }
                write_long(&mut out, 0x01, &[KIND_POST]);
                write_long(&mut out, 0x02, &post.post_id);
                write_long(&mut out, 0x03, post.geohash.as_bytes());
                write_long(&mut out, 0x04, post.content.as_bytes());
                write_long(&mut out, 0x05, &post.author_signing_key);
                write_long(&mut out, 0x06, post.author_nickname.as_bytes());
                write_long(&mut out, 0x07, &post.created_at.to_be_bytes());
                write_long(&mut out, 0x08, &post.expires_at.to_be_bytes());
                write_long(&mut out, 0x09, &[post.flags]);
                write_long(&mut out, 0x0A, &post.signature);
            }
            BoardWire::Tombstone(t) => {
                if t.post_id.len() != 16
                    || t.author_signing_key.len() != SIGNING_KEY_LENGTH
                    || t.signature.len() != SIGNATURE_LENGTH
                {
                    return None;
                }
                write_long(&mut out, 0x01, &[KIND_TOMBSTONE]);
                write_long(&mut out, 0x02, &t.post_id);
                write_long(&mut out, 0x05, &t.author_signing_key);
                write_long(&mut out, 0x0A, &t.signature);
                write_long(&mut out, 0x0B, &t.deleted_at.to_be_bytes());
            }
        }
        Some(out)
    }

    pub fn decode(data: &[u8]) -> Option<Self> {
        let mut kind: Option<u8> = None;
        let mut post_id: Option<Vec<u8>> = None;
        let mut geohash: Option<String> = None;
        let mut content: Option<String> = None;
        let mut author_signing_key: Option<Vec<u8>> = None;
        let mut author_nickname: Option<String> = None;
        let mut created_at: Option<u64> = None;
        let mut expires_at: Option<u64> = None;
        let mut flags: Option<u8> = None;
        let mut signature: Option<Vec<u8>> = None;
        let mut deleted_at: Option<u64> = None;

        let mut reader = TlvReader::long(data);
        while let Some((tag, value)) = reader.next_record() {
            match tag {
                0x01 if value.len() == 1 => kind = Some(value[0]),
                0x02 if value.len() == 16 => post_id = Some(value.to_vec()),
                0x03 => geohash = String::from_utf8(value.to_vec()).ok(),
                0x04 => content = String::from_utf8(value.to_vec()).ok(),
                0x05 if value.len() == SIGNING_KEY_LENGTH => {
                    author_signing_key = Some(value.to_vec())
                }
                0x06 => author_nickname = String::from_utf8(value.to_vec()).ok(),
                0x07 if value.len() == 8 => {
                    created_at = Some(u64::from_be_bytes(value.try_into().ok()?))
                }
                0x08 if value.len() == 8 => {
                    expires_at = Some(u64::from_be_bytes(value.try_into().ok()?))
                }
                0x09 if value.len() == 1 => flags = Some(value[0]),
                0x0A if value.len() == SIGNATURE_LENGTH => signature = Some(value.to_vec()),
                0x0B if value.len() == 8 => {
                    deleted_at = Some(u64::from_be_bytes(value.try_into().ok()?))
                }
                _ => {}
            }
        }

        match kind? {
            KIND_POST => {
                let post = BoardPostPacket {
                    post_id: post_id?,
                    geohash: geohash.unwrap_or_default(),
                    content: content?,
                    author_signing_key: author_signing_key?,
                    author_nickname: author_nickname.unwrap_or_default(),
                    created_at: created_at?,
                    expires_at: expires_at?,
                    flags: flags.unwrap_or(0),
                    signature: signature?,
                };
                if post.content.len() > CONTENT_MAX_BYTES
                    || !is_valid_geohash_or_empty(&post.geohash)
                    || post.expires_at <= post.created_at
                    || post.expires_at > post.created_at + MAX_LIFETIME_MS
                {
                    return None;
                }
                Some(BoardWire::Post(post))
            }
            KIND_TOMBSTONE => Some(BoardWire::Tombstone(BoardTombstonePacket {
                post_id: post_id?,
                deleted_at: deleted_at?,
                author_signing_key: author_signing_key?,
                signature: signature?,
            })),
            _ => None,
        }
    }

    pub fn verify_signature(&self) -> bool {
        match self {
            BoardWire::Post(p) => p.verify_signature(),
            BoardWire::Tombstone(t) => t.verify_signature(),
        }
    }

    /// Cheap TLV peek for the urgent flag (used by relay policy without a
    /// full decode).
    pub fn urgent_flag(data: &[u8]) -> bool {
        let mut reader = TlvReader::long(data);
        while let Some((tag, value)) = reader.next_record() {
            if tag == 0x09 && value.len() == 1 {
                return value[0] & FLAG_URGENT != 0;
            }
        }
        false
    }
}

fn is_valid_geohash_or_empty(s: &str) -> bool {
    s.is_empty() || is_valid_geohash(s)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::CypherIdentity;

    fn sample_post(author: &CypherIdentity) -> BoardPostPacket {
        let mut post = BoardPostPacket {
            post_id: vec![0x0A; 16],
            geohash: "dr5rsj7".into(),
            content: "water station open".into(),
            author_signing_key: author.signing_public().to_vec(),
            author_nickname: "ops".into(),
            created_at: 1_750_000_000_000,
            expires_at: 1_750_000_000_000 + 3_600_000,
            flags: FLAG_URGENT,
            signature: vec![0; 64],
        };
        post.signature = author.sign(&post.signing_bytes()).to_vec();
        post
    }

    #[test]
    fn post_round_trip_and_verify() {
        let author = CypherIdentity::generate();
        let post = sample_post(&author);
        let wire = BoardWire::Post(post.clone());
        let encoded = wire.encode().unwrap();
        let decoded = BoardWire::decode(&encoded).unwrap();
        assert_eq!(decoded, wire);
        assert!(decoded.verify_signature());
        assert!(BoardWire::urgent_flag(&encoded));
    }

    #[test]
    fn tampered_post_fails() {
        let author = CypherIdentity::generate();
        let mut post = sample_post(&author);
        post.content = "water station CLOSED".into();
        // Signature still over the original content.
        assert!(!BoardWire::Post(post).verify_signature());
    }

    #[test]
    fn rejects_bad_expiry_and_geohash() {
        let author = CypherIdentity::generate();
        let mut post = sample_post(&author);
        post.expires_at = post.created_at + MAX_LIFETIME_MS + 1;
        assert!(BoardWire::Post(post.clone()).encode().is_none());
        let mut post2 = sample_post(&author);
        post2.geohash = "DR5RSJ!".into(); // uppercase + invalid chars
        assert!(BoardWire::Post(post2).encode().is_none());
        let mut post3 = sample_post(&author);
        post3.expires_at = post3.created_at; // not after creation
        assert!(BoardWire::Post(post3).encode().is_none());
    }

    #[test]
    fn tombstone_round_trip_and_verify() {
        let author = CypherIdentity::generate();
        let mut t = BoardTombstonePacket {
            post_id: vec![0x0B; 16],
            deleted_at: 1_750_000_100_000,
            author_signing_key: author.signing_public().to_vec(),
            signature: vec![0; 64],
        };
        t.signature = author.sign(&t.signing_bytes()).to_vec();
        let wire = BoardWire::Tombstone(t);
        let encoded = wire.encode().unwrap();
        let decoded = BoardWire::decode(&encoded).unwrap();
        assert_eq!(decoded, wire);
        assert!(decoded.verify_signature());
    }

    #[test]
    fn mesh_local_board_with_empty_geohash() {
        let author = CypherIdentity::generate();
        let mut post = sample_post(&author);
        post.geohash = String::new();
        post.signature = author.sign(&post.signing_bytes()).to_vec();
        let wire = BoardWire::Post(post);
        assert!(wire.encode().is_some());
    }

    #[test]
    fn unknown_tlv_skipped() {
        let author = CypherIdentity::generate();
        let post = sample_post(&author);
        let mut encoded = BoardWire::Post(post).encode().unwrap();
        write_long(&mut encoded, 0x7F, b"future");
        assert!(BoardWire::decode(&encoded).is_some());
    }
}
