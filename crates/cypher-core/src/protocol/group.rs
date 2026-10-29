//! Private group chat: creator-signed state, roster blobs, and encrypted
//! group message envelopes (MessageType 0x25).

use crate::consts;
use crate::tlv::{write_long, TlvReader};
use crate::util::{hex_decode, hex_encode, sha256};
use chacha20poly1305::aead::{Aead, Payload};
use chacha20poly1305::{ChaCha20Poly1305, Key, KeyInit, Nonce};

pub const GROUP_ID_LENGTH: usize = consts::GROUP_ID_LENGTH;
pub const GROUP_KEY_LENGTH: usize = consts::GROUP_KEY_LENGTH;
pub const MAX_MEMBERS: usize = consts::GROUP_MAX_MEMBERS;
const NICKNAME_MAX_BYTES: usize = consts::GROUP_NICKNAME_MAX_BYTES;

pub const STATE_SIGNING_DOMAIN: &[u8] = b"bitchat-group-v1";
pub const MESSAGE_SIGNING_DOMAIN: &[u8] = b"bitchat-group-msg-v1";

/// A member as pinned in the creator-signed roster.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GroupMember {
    /// SHA-256 fingerprint (64 hex chars) of the member's Noise static key.
    pub fingerprint: String,
    /// Ed25519 signing public key (32 bytes, from their announce).
    pub signing_key: Vec<u8>,
    pub nickname: String,
}

/// Creator-managed encrypted group (metadata only; the symmetric key lives
/// in the host keystore).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BitchatGroup {
    /// 16 random bytes; travels in cleartext so relays can dedup.
    pub group_id: Vec<u8>,
    pub name: String,
    /// Bumps on every key rotation; messages are bound to their epoch.
    pub epoch: u32,
    pub members: Vec<GroupMember>,
    /// Fingerprint of the creator — the only identity allowed to sign state.
    pub creator_fingerprint: String,
}

impl BitchatGroup {
    pub fn creator(&self) -> Option<&GroupMember> {
        self.members
            .iter()
            .find(|m| m.fingerprint == self.creator_fingerprint)
    }

    pub fn is_member(&self, fingerprint: &str) -> bool {
        self.members.iter().any(|m| m.fingerprint == fingerprint)
    }

    pub fn member_by_signing_key(&self, signing_key: &[u8]) -> Option<&GroupMember> {
        self.members.iter().find(|m| m.signing_key == signing_key)
    }

    /// Does `other` name the same creator (fingerprint AND signing key)?
    /// A group keeps the creator it was created with; state naming anyone
    /// else is a takeover attempt however validly self-signed.
    pub fn has_same_creator(&self, other: &BitchatGroup) -> bool {
        match (self.creator(), other.creator()) {
            (Some(a), Some(b)) => a.fingerprint == b.fingerprint && a.signing_key == b.signing_key,
            _ => false,
        }
    }

    /// Virtual conversation peer ID (`group_` + hex).
    pub fn peer_id(&self) -> crate::PeerId {
        crate::PeerId::new(format!("group_{}", hex_encode(&self.group_id)))
    }
}

// ---------------------------------------------------------------------------
// Roster wire form
// ---------------------------------------------------------------------------

pub struct GroupRosterCoding;

impl GroupRosterCoding {
    /// Deterministic roster blob: count byte, then per member the raw 32-byte
    /// fingerprint, 32-byte signing key, and length-prefixed UTF-8 nickname.
    /// The creator signature covers the SHA-256 of these exact bytes.
    pub fn encode(members: &[GroupMember]) -> Option<Vec<u8>> {
        if members.len() > MAX_MEMBERS {
            return None;
        }
        let mut out = Vec::with_capacity(1 + members.len() * 69);
        out.push(members.len() as u8);
        for member in members {
            let fingerprint = hex_decode(&member.fingerprint.to_lowercase())?;
            if fingerprint.len() != 32 || member.signing_key.len() != 32 {
                return None;
            }
            out.extend_from_slice(&fingerprint);
            out.extend_from_slice(&member.signing_key);
            let nickname = truncate_nickname(&member.nickname);
            out.push(nickname.len() as u8);
            out.extend_from_slice(&nickname);
        }
        Some(out)
    }

    pub fn decode(data: &[u8]) -> Option<Vec<GroupMember>> {
        let (&count, rest) = data.split_first()?;
        if count as usize > MAX_MEMBERS {
            return None;
        }
        let mut members = Vec::with_capacity(count as usize);
        let mut off = 0usize;
        for _ in 0..count {
            if rest.len() - off < 32 + 32 + 1 {
                return None;
            }
            let fingerprint = hex_encode(&rest[off..off + 32]);
            off += 32;
            let signing_key = rest[off..off + 32].to_vec();
            off += 32;
            let nick_len = rest[off] as usize;
            off += 1;
            if rest.len() - off < nick_len {
                return None;
            }
            let nickname = String::from_utf8(rest[off..off + nick_len].to_vec()).ok()?;
            off += nick_len;
            members.push(GroupMember {
                fingerprint,
                signing_key,
                nickname,
            });
        }
        Some(members)
    }
}

fn truncate_nickname(nickname: &str) -> Vec<u8> {
    let bytes = nickname.as_bytes();
    if bytes.len() <= NICKNAME_MAX_BYTES {
        return bytes.to_vec();
    }
    // Truncate on a char boundary so the byte prefix is valid UTF-8.
    let mut end = NICKNAME_MAX_BYTES;
    while end > 0 && !nickname.is_char_boundary(end) {
        end -= 1;
    }
    bytes[..end].to_vec()
}

// ---------------------------------------------------------------------------
// Group state payload (Noise payloads 0x06 / 0x07)
// ---------------------------------------------------------------------------

/// Creator-signed group state — invites and key updates share this form.
/// TLV (u16 BE lengths): `0x01 groupID(16)`, `0x02 name`, `0x03 key(32)`,
/// `0x04 epoch(4)`, `0x05 roster`, `0x06 creatorFingerprint(32 raw)`,
/// `0x07 signature(64)`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GroupStatePayload {
    pub group_id: Vec<u8>,
    pub name: String,
    /// Symmetric ChaCha20-Poly1305 group key (32 bytes) for `epoch`.
    pub key: Vec<u8>,
    pub epoch: u32,
    pub members: Vec<GroupMember>,
    pub creator_fingerprint: String,
    pub signature: Vec<u8>,
}

impl GroupStatePayload {
    /// Bytes the creator signs: domain | groupID | epoch | SHA256(key) |
    /// SHA256(roster) | SHA256(name). Hashing keeps the signed content
    /// fixed-size and stops a relay from swapping the display name.
    pub fn signing_content(
        group_id: &[u8],
        epoch: u32,
        key: &[u8],
        roster_blob: &[u8],
        name: &str,
    ) -> Vec<u8> {
        let mut content = STATE_SIGNING_DOMAIN.to_vec();
        content.extend_from_slice(group_id);
        content.extend_from_slice(&epoch.to_be_bytes());
        content.extend_from_slice(&sha256(key));
        content.extend_from_slice(&sha256(roster_blob));
        content.extend_from_slice(&sha256(name.as_bytes()));
        content
    }

    pub fn make_signed(
        group: &BitchatGroup,
        key: &[u8],
        sign: &dyn Fn(&[u8]) -> Vec<u8>,
    ) -> Option<Self> {
        let roster_blob = GroupRosterCoding::encode(&group.members)?;
        if group.group_id.len() != GROUP_ID_LENGTH || key.len() != GROUP_KEY_LENGTH {
            return None;
        }
        let content =
            Self::signing_content(&group.group_id, group.epoch, key, &roster_blob, &group.name);
        let signature = sign(&content);
        if signature.len() != 64 {
            return None;
        }
        Some(GroupStatePayload {
            group_id: group.group_id.clone(),
            name: group.name.clone(),
            key: key.to_vec(),
            epoch: group.epoch,
            members: group.members.clone(),
            creator_fingerprint: group.creator_fingerprint.clone(),
            signature,
        })
    }

    pub fn encode(&self) -> Option<Vec<u8>> {
        if self.group_id.len() != GROUP_ID_LENGTH || self.key.len() != GROUP_KEY_LENGTH {
            return None;
        }
        let roster = GroupRosterCoding::encode(&self.members)?;
        let creator = hex_decode(&self.creator_fingerprint.to_lowercase())?;
        if creator.len() != 32 || self.signature.len() != 64 {
            return None;
        }
        let mut out = Vec::new();
        write_long(&mut out, 0x01, &self.group_id);
        write_long(&mut out, 0x02, self.name.as_bytes());
        write_long(&mut out, 0x03, &self.key);
        write_long(&mut out, 0x04, &self.epoch.to_be_bytes());
        write_long(&mut out, 0x05, &roster);
        write_long(&mut out, 0x06, &creator);
        write_long(&mut out, 0x07, &self.signature);
        Some(out)
    }

    pub fn decode(data: &[u8]) -> Option<Self> {
        let mut group_id: Option<Vec<u8>> = None;
        let mut name: Option<String> = None;
        let mut key: Option<Vec<u8>> = None;
        let mut epoch: Option<u32> = None;
        let mut roster: Option<Vec<u8>> = None;
        let mut creator: Option<Vec<u8>> = None;
        let mut signature: Option<Vec<u8>> = None;

        let mut reader = TlvReader::long(data);
        while let Some((tag, value)) = reader.next_record() {
            match tag {
                0x01 if value.len() == GROUP_ID_LENGTH => group_id = Some(value.to_vec()),
                0x02 => name = String::from_utf8(value.to_vec()).ok(),
                0x03 if value.len() == GROUP_KEY_LENGTH => key = Some(value.to_vec()),
                0x04 if value.len() == 4 => {
                    epoch = Some(u32::from_be_bytes(value.try_into().ok()?))
                }
                0x05 => roster = Some(value.to_vec()),
                0x06 if value.len() == 32 => creator = Some(value.to_vec()),
                0x07 if value.len() == 64 => signature = Some(value.to_vec()),
                _ => {}
            }
        }
        let members = GroupRosterCoding::decode(&roster?)?;
        Some(GroupStatePayload {
            group_id: group_id?,
            name: name?,
            key: key?,
            epoch: epoch?,
            members,
            creator_fingerprint: hex_encode(&creator?),
            signature: signature?,
        })
    }

    /// Verify the creator signature against the roster-pinned creator key.
    pub fn verify_creator_signature(&self) -> bool {
        let Some(roster) = GroupRosterCoding::encode(&self.members) else {
            return false;
        };
        let content =
            Self::signing_content(&self.group_id, self.epoch, &self.key, &roster, &self.name);
        let Some(creator) = self
            .members
            .iter()
            .find(|m| m.fingerprint == self.creator_fingerprint)
        else {
            return false;
        };
        let Ok(key): Result<[u8; 32], _> = creator.signing_key.clone().try_into() else {
            return false;
        };
        crate::identity::CypherIdentity::verify(&self.signature, &content, &key)
    }
}

// ---------------------------------------------------------------------------
// Group message envelope (MessageType 0x25)
// ---------------------------------------------------------------------------

/// TLV: `0x01 groupID(16)`, `0x02 epoch(4)`, `0x03 nonce(12)`,
/// `0x04 ciphertext(ct ‖ 16 B tag)`. AAD = groupID ‖ epoch.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GroupMessageEnvelope {
    pub group_id: Vec<u8>,
    pub epoch: u32,
    pub nonce: Vec<u8>,
    pub ciphertext: Vec<u8>,
}

impl GroupMessageEnvelope {
    pub fn encode(&self) -> Option<Vec<u8>> {
        if self.group_id.len() != GROUP_ID_LENGTH
            || self.nonce.len() != 12
            || self.ciphertext.is_empty()
        {
            return None;
        }
        let mut out = Vec::new();
        write_long(&mut out, 0x01, &self.group_id);
        write_long(&mut out, 0x02, &self.epoch.to_be_bytes());
        write_long(&mut out, 0x03, &self.nonce);
        write_long(&mut out, 0x04, &self.ciphertext);
        Some(out)
    }

    pub fn decode(data: &[u8]) -> Option<Self> {
        let mut group_id: Option<Vec<u8>> = None;
        let mut epoch: Option<u32> = None;
        let mut nonce: Option<Vec<u8>> = None;
        let mut ciphertext: Option<Vec<u8>> = None;
        let mut reader = TlvReader::long(data);
        while let Some((tag, value)) = reader.next_record() {
            match tag {
                0x01 if value.len() == GROUP_ID_LENGTH => group_id = Some(value.to_vec()),
                0x02 if value.len() == 4 => {
                    epoch = Some(u32::from_be_bytes(value.try_into().ok()?))
                }
                0x03 if value.len() == 12 => nonce = Some(value.to_vec()),
                0x04 if !value.is_empty() => ciphertext = Some(value.to_vec()),
                _ => {}
            }
        }
        Some(GroupMessageEnvelope {
            group_id: group_id?,
            epoch: epoch?,
            nonce: nonce?,
            ciphertext: ciphertext?,
        })
    }

    fn aad(&self) -> Vec<u8> {
        let mut aad = self.group_id.clone();
        aad.extend_from_slice(&self.epoch.to_be_bytes());
        aad
    }
}

/// Inner plaintext TLV: `0x01 messageID`, `0x02 senderSigningKey(32)`,
/// `0x03 senderNickname`, `0x04 timestampMs(u64)`, `0x05 content`,
/// `0x06 signature(64)`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GroupMessagePlaintext {
    pub message_id: String,
    pub sender_signing_key: Vec<u8>,
    pub sender_nickname: String,
    pub timestamp_ms: u64,
    pub content: String,
    pub signature: Vec<u8>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum GroupCryptoError {
    MalformedPayload,
    SigningFailed,
    SealFailed,
    DecryptionFailed,
    BadSenderSignature,
}

pub struct GroupCrypto;

impl GroupCrypto {
    /// Bytes the sender signs: domain | groupID | epoch | messageID |
    /// timestamp | content. Covering the epoch stops re-sealing under a
    /// later epoch key.
    pub fn message_signing_content(
        group_id: &[u8],
        epoch: u32,
        message_id: &str,
        timestamp_ms: u64,
        content: &str,
    ) -> Vec<u8> {
        let mut data = MESSAGE_SIGNING_DOMAIN.to_vec();
        data.extend_from_slice(group_id);
        data.extend_from_slice(&epoch.to_be_bytes());
        data.extend_from_slice(message_id.as_bytes());
        data.extend_from_slice(&timestamp_ms.to_be_bytes());
        data.extend_from_slice(content.as_bytes());
        data
    }

    /// Seal a group message: signed inner TLV, encrypted with the epoch key;
    /// groupID + epoch bound as AAD. Returns the 0x25 packet payload.
    #[allow(clippy::too_many_arguments)]
    pub fn seal_message(
        content: &str,
        message_id: &str,
        sender_nickname: &str,
        sender_signing_key: &[u8; 32],
        timestamp_ms: u64,
        group_id: &[u8],
        epoch: u32,
        key: &[u8; 32],
        sign: &dyn Fn(&[u8]) -> Vec<u8>,
    ) -> Result<Vec<u8>, GroupCryptoError> {
        let signing_content =
            Self::message_signing_content(group_id, epoch, message_id, timestamp_ms, content);
        let signature = sign(&signing_content);
        if signature.len() != 64 {
            return Err(GroupCryptoError::SigningFailed);
        }

        let mut inner = Vec::new();
        write_long(&mut inner, 0x01, message_id.as_bytes());
        write_long(&mut inner, 0x02, sender_signing_key);
        write_long(&mut inner, 0x03, sender_nickname.as_bytes());
        write_long(&mut inner, 0x04, &timestamp_ms.to_be_bytes());
        write_long(&mut inner, 0x05, content.as_bytes());
        write_long(&mut inner, 0x06, &signature);

        let mut nonce_bytes = [0u8; 12];
        use rand_core::RngCore;
        rand_core::OsRng.fill_bytes(&mut nonce_bytes);
        let cipher = ChaCha20Poly1305::new(Key::from_slice(key));
        let aad = {
            let mut aad = group_id.to_vec();
            aad.extend_from_slice(&epoch.to_be_bytes());
            aad
        };
        let ct = cipher
            .encrypt(
                Nonce::from_slice(&nonce_bytes),
                Payload {
                    msg: &inner,
                    aad: &aad,
                },
            )
            .map_err(|_| GroupCryptoError::SealFailed)?;

        let envelope = GroupMessageEnvelope {
            group_id: group_id.to_vec(),
            epoch,
            nonce: nonce_bytes.to_vec(),
            ciphertext: ct,
        };
        envelope.encode().ok_or(GroupCryptoError::SealFailed)
    }

    /// Open an envelope: decrypt, parse the inner TLV, verify the sender's
    /// Ed25519 signature. Roster membership is the CALLER's check.
    pub fn open_message(
        envelope: &GroupMessageEnvelope,
        key: &[u8; 32],
    ) -> Result<GroupMessagePlaintext, GroupCryptoError> {
        if envelope.ciphertext.len() <= 16 {
            return Err(GroupCryptoError::DecryptionFailed);
        }
        let cipher = ChaCha20Poly1305::new(Key::from_slice(key));
        let plaintext = cipher
            .decrypt(
                Nonce::from_slice(&envelope.nonce),
                Payload {
                    msg: &envelope.ciphertext,
                    aad: &envelope.aad(),
                },
            )
            .map_err(|_| GroupCryptoError::DecryptionFailed)?;

        let mut message_id: Option<String> = None;
        let mut sender_signing_key: Option<Vec<u8>> = None;
        let mut sender_nickname: Option<String> = None;
        let mut timestamp_ms: Option<u64> = None;
        let mut content: Option<String> = None;
        let mut signature: Option<Vec<u8>> = None;
        let mut reader = TlvReader::long(&plaintext);
        while let Some((tag, value)) = reader.next_record() {
            match tag {
                0x01 => message_id = String::from_utf8(value.to_vec()).ok(),
                0x02 if value.len() == 32 => sender_signing_key = Some(value.to_vec()),
                0x03 => sender_nickname = String::from_utf8(value.to_vec()).ok(),
                0x04 if value.len() == 8 => {
                    timestamp_ms = Some(u64::from_be_bytes(match value.try_into() {
                        Ok(v) => v,
                        Err(_) => return Err(GroupCryptoError::MalformedPayload),
                    }))
                }
                0x05 => content = String::from_utf8(value.to_vec()).ok(),
                0x06 if value.len() == 64 => signature = Some(value.to_vec()),
                _ => {}
            }
        }

        let plaintext = GroupMessagePlaintext {
            message_id: message_id.ok_or(GroupCryptoError::MalformedPayload)?,
            sender_signing_key: sender_signing_key.ok_or(GroupCryptoError::MalformedPayload)?,
            sender_nickname: sender_nickname.ok_or(GroupCryptoError::MalformedPayload)?,
            timestamp_ms: timestamp_ms.ok_or(GroupCryptoError::MalformedPayload)?,
            content: content.ok_or(GroupCryptoError::MalformedPayload)?,
            signature: signature.ok_or(GroupCryptoError::MalformedPayload)?,
        };

        let signing_content = Self::message_signing_content(
            &envelope.group_id,
            envelope.epoch,
            &plaintext.message_id,
            plaintext.timestamp_ms,
            &plaintext.content,
        );
        let key_arr: [u8; 32] = match plaintext.sender_signing_key.clone().try_into() {
            Ok(k) => k,
            Err(_) => return Err(GroupCryptoError::MalformedPayload),
        };
        if !crate::identity::CypherIdentity::verify(
            &plaintext.signature,
            &signing_content,
            &key_arr,
        ) {
            return Err(GroupCryptoError::BadSenderSignature);
        }
        Ok(plaintext)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::CypherIdentity;

    fn sample_group(creator: &CypherIdentity, other: &CypherIdentity) -> BitchatGroup {
        BitchatGroup {
            group_id: vec![0x77; 16],
            name: "rebels".into(),
            epoch: 1,
            members: vec![
                GroupMember {
                    fingerprint: creator.fingerprint(),
                    signing_key: creator.signing_public().to_vec(),
                    nickname: "leader".into(),
                },
                GroupMember {
                    fingerprint: other.fingerprint(),
                    signing_key: other.signing_public().to_vec(),
                    nickname: "member".into(),
                },
            ],
            creator_fingerprint: creator.fingerprint(),
        }
    }

    #[test]
    fn roster_round_trip() {
        let creator = CypherIdentity::generate();
        let other = CypherIdentity::generate();
        let group = sample_group(&creator, &other);
        let blob = GroupRosterCoding::encode(&group.members).unwrap();
        let decoded = GroupRosterCoding::decode(&blob).unwrap();
        assert_eq!(decoded, group.members);
        // First byte is the member count.
        assert_eq!(blob[0], 2);
    }

    #[test]
    fn state_sign_and_round_trip() {
        let creator = CypherIdentity::generate();
        let other = CypherIdentity::generate();
        let group = sample_group(&creator, &other);
        let key = [0x33u8; 32];
        let state =
            GroupStatePayload::make_signed(&group, &key, &|d| creator.sign(d).to_vec()).unwrap();
        assert!(state.verify_creator_signature());
        let encoded = state.encode().unwrap();
        let decoded = GroupStatePayload::decode(&encoded).unwrap();
        assert_eq!(decoded, state);
        assert!(decoded.verify_creator_signature());

        // A forged state (different key) fails verification.
        let mut forged = decoded.clone();
        forged.key = vec![0x44; 32];
        assert!(!forged.verify_creator_signature());
    }

    #[test]
    fn seal_and_open_message() {
        let creator = CypherIdentity::generate();
        let other = CypherIdentity::generate();
        let group_id = [0x77u8; 16];
        let key = [0x55u8; 32];
        let payload = GroupCrypto::seal_message(
            "meet at dawn",
            "gm-1",
            "leader",
            &creator.signing_public(),
            1_750_000_000_000,
            &group_id,
            3,
            &key,
            &|d| creator.sign(d).to_vec(),
        )
        .unwrap();
        let envelope = GroupMessageEnvelope::decode(&payload).unwrap();
        let opened = GroupCrypto::open_message(&envelope, &key).unwrap();
        assert_eq!(opened.content, "meet at dawn");
        assert_eq!(opened.sender_nickname, "leader");
        assert_eq!(opened.sender_signing_key, creator.signing_public().to_vec());

        // Wrong key fails.
        assert_eq!(
            GroupCrypto::open_message(&envelope, &[0x66; 32]).unwrap_err(),
            GroupCryptoError::DecryptionFailed
        );

        // Tampered ciphertext fails.
        let mut bad = envelope.clone();
        let last = bad.ciphertext.len() - 1;
        bad.ciphertext[last] ^= 1;
        assert_eq!(
            GroupCrypto::open_message(&bad, &key).unwrap_err(),
            GroupCryptoError::DecryptionFailed
        );

        // Cross-epoch replay fails (AAD binding).
        let mut replayed = envelope.clone();
        replayed.epoch = 4;
        assert_eq!(
            GroupCrypto::open_message(&replayed, &key).unwrap_err(),
            GroupCryptoError::DecryptionFailed
        );
        let _ = other;
    }

    #[test]
    fn nickname_truncated_on_char_boundary() {
        let member = GroupMember {
            fingerprint: "ab".repeat(32),
            signing_key: vec![1; 32],
            nickname: "🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉".into(),
        };
        let blob = GroupRosterCoding::encode(&[member.clone()]).unwrap();
        let decoded = GroupRosterCoding::decode(&blob).unwrap();
        assert!(decoded[0].nickname.as_bytes().len() <= NICKNAME_MAX_BYTES);
        assert!(decoded[0].nickname.chars().next().is_some());
    }
}
