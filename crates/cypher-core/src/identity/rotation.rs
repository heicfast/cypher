//! Rotating peer IDs and pairwise recognition tags — exact port of
//! bitchat's `PeerIDRotation.swift` (BitFoundation). Deliberately pure: no
//! I/O, no clock of its own — the derivations both platforms must agree on
//! before anyone wires them into a transport.
//!
//! Golden vectors pinned from `PeerIDRotationTests.swift` (cross-checked
//! with an independent implementation).

use crate::util::hmac_sha256;

/// Seconds per rotation epoch.
pub const ROTATION_PERIOD_S: u64 = 3600;
/// Bytes of an ID or tag placed on the wire (existing 8-byte header field).
pub const ID_LENGTH: usize = 8;
/// Fixed number of tag slots in an announce (constant 64-byte block).
pub const TAG_SLOTS: usize = 8;
/// Always `tagSlots * idLength` bytes.
pub const TAG_BLOCK_LENGTH: usize = TAG_SLOTS * ID_LENGTH;

const ROTATION_INFO: &[u8] = b"bitchat-peer-rotation-v1";
const PEER_ID_CONTEXT: &[u8] = b"bitchat-peer-id-v2";
const RECOGNITION_INFO: &[u8] = b"bitchat-recognition-v1";
const BINDING_CONTEXT: &[u8] = b"bitchat-peerid-binding-v1";

/// RFC 5869 HKDF-SHA256 (extract + expand) with `info`, 32-byte output.
/// Swift: `HKDF<SHA256>.deriveKey(inputKeyMaterial:, info:, outputByteCount:)`
/// — no salt, so the extract salt is 32 zero bytes.
fn hkdf_sha256(ikm: &[u8], info: &[u8], out_len: usize) -> Vec<u8> {
    let prk = hmac_sha256(&[0u8; 32][..], ikm).to_vec();
    let mut out = Vec::with_capacity(out_len);
    let mut t: Vec<u8> = Vec::new();
    let mut counter: u8 = 1;
    while out.len() < out_len {
        let mut input = t.clone();
        input.extend_from_slice(info);
        input.push(counter);
        t = hmac_sha256(&prk, &input).to_vec();
        out.extend_from_slice(&t);
        counter += 1;
    }
    out.truncate(out_len);
    out
}

fn fixed_width(data: &[u8], width: usize) -> Vec<u8> {
    let mut out = data.iter().copied().take(width).collect::<Vec<u8>>();
    if out.len() < width {
        out.resize(width, 0);
    }
    out
}

/// Epoch number for a point in time (wall-clock derived).
pub fn epoch(unix_seconds: u64) -> u32 {
    (unix_seconds / ROTATION_PERIOD_S) as u32
}

/// Epochs to test when matching, oldest first (±1 absorbs clock skew and
/// boundary crossings); at epoch 0 the window is [0, 1].
pub fn candidate_epochs(unix_seconds: u64) -> Vec<u32> {
    let current = epoch(unix_seconds);
    if current == 0 {
        vec![0, 1]
    } else {
        vec![current - 1, current, current + 1]
    }
}

/// Long-lived rotation secret from the Noise static **private** key —
/// derived from private material so no observer can predict any ID.
pub fn rotation_secret(noise_static_private_key: &[u8]) -> Vec<u8> {
    hkdf_sha256(noise_static_private_key, ROTATION_INFO, 32)
}

/// This device's peer ID for a given epoch: HMAC-SHA256(secret,
/// "bitchat-peer-id-v2" ‖ u32be(epoch))[0..8].
pub fn peer_id(rotation_secret: &[u8], epoch: u32) -> Vec<u8> {
    let mut message = PEER_ID_CONTEXT.to_vec();
    message.extend_from_slice(&epoch.to_be_bytes());
    let mac = hmac_sha256(rotation_secret, &message).to_vec();
    mac[..ID_LENGTH].to_vec()
}

/// Convenience: the ID this device should be using now.
pub fn current_peer_id(noise_static_private_key: &[u8], unix_seconds: u64) -> Vec<u8> {
    peer_id(
        &rotation_secret(noise_static_private_key),
        epoch(unix_seconds),
    )
}

/// Symmetric recognition key for a pair, from their X25519 shared secret.
pub fn recognition_key(shared_secret: &[u8]) -> Vec<u8> {
    hkdf_sha256(shared_secret, RECOGNITION_INFO, 32)
}

/// The directional tag `sender` puts in its announce for `recipient` this
/// epoch: epoch(u32be) ‖ senderStatic(32) ‖ recipientStatic(32) ‖ peerID(8),
/// HMAC'd with the recognition key, first 8 bytes.
pub fn recognition_tag(
    recognition_key: &[u8],
    epoch: u32,
    sender_static_public_key: &[u8],
    recipient_static_public_key: &[u8],
    peer_id: &[u8],
) -> Vec<u8> {
    let mut message = Vec::new();
    message.extend_from_slice(&epoch.to_be_bytes());
    message.extend_from_slice(&fixed_width(sender_static_public_key, 32));
    message.extend_from_slice(&fixed_width(recipient_static_public_key, 32));
    message.extend_from_slice(&fixed_width(peer_id, ID_LENGTH));
    let mac = hmac_sha256(recognition_key, &message).to_vec();
    mac[..ID_LENGTH].to_vec()
}

/// Split a received block into candidate tags; only a block of exactly
/// `tagSlots * idLength` is accepted.
pub fn tags_from_block(block: &[u8]) -> Option<Vec<Vec<u8>>> {
    if block.len() != TAG_BLOCK_LENGTH {
        return None;
    }
    Some(block.chunks(ID_LENGTH).map(|c| c.to_vec()).collect())
}

/// Whether any slot holds the tag we expect from a specific peer. Every
/// slot is examined even after a match (constant-time behavior).
pub fn block_matches(
    block: &[u8],
    recognition_key: &[u8],
    sender_static_public_key: &[u8],
    recipient_static_public_key: &[u8],
    peer_id: &[u8],
    unix_seconds: u64,
) -> bool {
    let Some(slots) = tags_from_block(block) else {
        return false;
    };
    let expected: Vec<Vec<u8>> = candidate_epochs(unix_seconds)
        .into_iter()
        .map(|e| {
            recognition_tag(
                recognition_key,
                e,
                sender_static_public_key,
                recipient_static_public_key,
                peer_id,
            )
        })
        .collect();
    let mut matched = false;
    for slot in &slots {
        for candidate in &expected {
            // Constant-time comparison.
            let mut diff = 0u8;
            for (a, b) in slot.iter().zip(candidate.iter()) {
                diff |= a ^ b;
            }
            if diff == 0 {
                matched = true;
            }
        }
    }
    matched
}

/// Canonical bytes proving a rotating ID belongs to a static key:
/// "bitchat-peerid-binding-v1" ‖ u32be(epoch) ‖ peerID(8) ‖ static(32).
pub fn binding_message(epoch: u32, peer_id: &[u8], noise_static_public_key: &[u8]) -> Vec<u8> {
    let mut out = BINDING_CONTEXT.to_vec();
    out.extend_from_slice(&epoch.to_be_bytes());
    out.extend_from_slice(&fixed_width(peer_id, ID_LENGTH));
    out.extend_from_slice(&fixed_width(noise_static_public_key, 32));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Golden from PeerIDRotationTests.swift:56-63 —
    /// ikm = 01 02 … 20 (bytes 1..=32).
    #[test]
    fn golden_rotation_secret() {
        let ikm: Vec<u8> = (1u8..=32).collect();
        let secret = rotation_secret(&ikm);
        assert_eq!(
            crate::util::hex_encode(&secret),
            "fb82dfec0c0a2a4677beca44e2f72c80e7c5de773dd5fce6ee47af83d3c25f09"
        );
    }

    /// Golden from PeerIDRotationTests.swift:65-78.
    #[test]
    fn golden_peer_id_epoch_100() {
        let ikm: Vec<u8> = (1u8..=32).collect();
        let secret = rotation_secret(&ikm);
        let id = peer_id(&secret, 100);
        assert_eq!(crate::util::hex_encode(&id), "f7c08c528506a374");
    }

    /// Golden from PeerIDRotationTests.swift:182-196 — recognition key from
    /// ikm 0x42×32, then tag over (epoch 100, sender 0x0A×32, recipient
    /// 0x0B×32, peerID 0xA1×8).
    #[test]
    fn golden_recognition_tag() {
        let key = recognition_key(&[0x42; 32]);
        let tag = recognition_tag(&key, 100, &[0x0A; 32], &[0x0B; 32], &[0xA1; 8]);
        assert_eq!(crate::util::hex_encode(&tag), "4568f61d61d6cbfb");
    }

    /// Epoch math from PeerIDRotationTests.swift:33-52.
    #[test]
    fn epoch_math() {
        assert_eq!(epoch(1_784_000_000), 495_555);
        assert_eq!(candidate_epochs(360_000), vec![99, 100, 101]);
        assert_eq!(candidate_epochs(0), vec![0, 1]);
    }

    /// Block rules: exact 64 bytes; 63/65 rejected (tests :214-259).
    #[test]
    fn tag_block_rules() {
        assert_eq!(TAG_BLOCK_LENGTH, 64);
        assert!(tags_from_block(&[0u8; 63]).is_none());
        assert!(tags_from_block(&[0u8; 65]).is_none());
        let block = [0u8; 64];
        let slots = tags_from_block(&block).unwrap();
        assert_eq!(slots.len(), TAG_SLOTS);
        assert!(slots.iter().all(|s| s.len() == ID_LENGTH));
    }

    /// Directional: swapping sender/recipient must NOT match.
    #[test]
    fn tags_are_directional() {
        let key = recognition_key(&[0x42; 32]);
        let epoch_s = 360_000; // epoch 100
        let sender: &[u8] = &[0x0A; 32];
        let recipient: &[u8] = &[0x0B; 32];
        let id: &[u8] = &[0xA1; 8];
        let tag = recognition_tag(&key, epoch(epoch_s), sender, recipient, id);
        // Build a block whose only real slot is the tag (padding zeros).
        let mut block = vec![0u8; TAG_BLOCK_LENGTH];
        block[..8].copy_from_slice(&tag);
        assert!(block_matches(&block, &key, sender, recipient, id, epoch_s));
        assert!(!block_matches(&block, &key, recipient, sender, id, epoch_s));
    }

    /// Binding message: fixed widths, context-separated.
    #[test]
    fn binding_message_layout() {
        let msg = binding_message(7, &[0xC1; 8], &[0xD2; 32]);
        assert_eq!(&msg[..25], b"bitchat-peerid-binding-v1");
        assert_eq!(&msg[25..29], &7u32.to_be_bytes());
        assert_eq!(&msg[29..37], &[0xC1; 8]);
        assert_eq!(&msg[37..69], &[0xD2; 32]);
        assert_eq!(msg.len(), 69);
    }
}
