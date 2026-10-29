//! Wire message types and frame flags (bitchat-compatible).

/// Packet type byte carried at offset 1 of every frame.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Hash)]
#[repr(u8)]
pub enum MessageType {
    /// Identity announcement (TLV payload, Ed25519 signed at packet level).
    Announce = 0x01,
    /// Public chat message; payload is raw UTF-8 content.
    Message = 0x02,
    /// Peer leaving the mesh.
    Leave = 0x03,
    /// Store-and-forward courier envelope (opaque Noise X seal).
    CourierEnvelope = 0x04,
    /// Noise XX handshake message (raw Noise bytes).
    NoiseHandshake = 0x10,
    /// Noise-encrypted transport frame (`[4B nonce][ct][16B tag]`).
    NoiseEncrypted = 0x11,
    /// Fragment of a larger packet (13-byte inner header).
    Fragment = 0x20,
    /// Link-local gossip sync request (never relayed).
    RequestSync = 0x21,
    /// File transfer (broadcast or legacy directed).
    FileTransfer = 0x22,
    /// Bulletin board post/tombstone.
    BoardPost = 0x23,
    /// One-time prekey bundle (gossiped).
    PrekeyBundle = 0x24,
    /// Group message envelope (ChaChaPoly, AAD = groupID || epoch).
    GroupMessage = 0x25,
    /// Mesh ping probe.
    Ping = 0x26,
    /// Mesh pong reply.
    Pong = 0x27,
    /// Nostr carrier (internet bridge — relayed for compat, never consumed).
    NostrCarrier = 0x28,
    /// Public push-to-talk voice burst.
    VoiceFrame = 0x29,
    /// Rotating-identity announce (parsed but dormant, like bitchat).
    AnnounceV2 = 0x2C,
}

impl MessageType {
    pub fn from_u8(v: u8) -> Option<Self> {
        Some(match v {
            0x01 => MessageType::Announce,
            0x02 => MessageType::Message,
            0x03 => MessageType::Leave,
            0x04 => MessageType::CourierEnvelope,
            0x10 => MessageType::NoiseHandshake,
            0x11 => MessageType::NoiseEncrypted,
            0x20 => MessageType::Fragment,
            0x21 => MessageType::RequestSync,
            0x22 => MessageType::FileTransfer,
            0x23 => MessageType::BoardPost,
            0x24 => MessageType::PrekeyBundle,
            0x25 => MessageType::GroupMessage,
            0x26 => MessageType::Ping,
            0x27 => MessageType::Pong,
            0x28 => MessageType::NostrCarrier,
            0x29 => MessageType::VoiceFrame,
            0x2C => MessageType::AnnounceV2,
            _ => return None,
        })
    }

    /// Raw type byte (also used for unknown types we relay transparently).
    pub fn as_u8(self) -> u8 {
        self as u8
    }
}

/// Frame flag bits (offset 11 in both v1 and v2 headers).
pub mod flags {
    pub const HAS_RECIPIENT: u8 = 0x01;
    pub const HAS_SIGNATURE: u8 = 0x02;
    pub const IS_COMPRESSED: u8 = 0x04;
    /// v2 frames only; ignored when decoding v1.
    pub const HAS_ROUTE: u8 = 0x08;
    pub const IS_RSR: u8 = 0x10;
}

/// Noise payload type byte (first plaintext byte of a `noiseEncrypted` body).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
#[repr(u8)]
pub enum NoisePayloadType {
    PrivateMessage = 0x01,
    ReadReceipt = 0x02,
    Delivered = 0x03,
    GroupInvite = 0x06,
    GroupKeyUpdate = 0x07,
    VoiceFrame = 0x08,
    /// Legacy prerelease alias for PrivateFile — accepted, never emitted.
    LegacyPrivateFile = 0x09,
    VerifyChallenge = 0x10,
    VerifyResponse = 0x11,
    Vouch = 0x12,
    PrivateFile = 0x20,
    AuthenticatedPeerState = 0x21,
}

impl NoisePayloadType {
    pub fn from_u8(v: u8) -> Option<Self> {
        Some(match v {
            0x01 => Self::PrivateMessage,
            0x02 => Self::ReadReceipt,
            0x03 => Self::Delivered,
            0x06 => Self::GroupInvite,
            0x07 => Self::GroupKeyUpdate,
            0x08 => Self::VoiceFrame,
            0x09 => Self::LegacyPrivateFile,
            0x10 => Self::VerifyChallenge,
            0x11 => Self::VerifyResponse,
            0x12 => Self::Vouch,
            0x20 => Self::PrivateFile,
            0x21 => Self::AuthenticatedPeerState,
            _ => return None,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn type_round_trip() {
        for v in [0x01u8, 0x02, 0x03, 0x04, 0x10, 0x11, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2C] {
            let t = MessageType::from_u8(v).expect("known type");
            assert_eq!(t.as_u8(), v);
        }
        assert!(MessageType::from_u8(0x05).is_none());
        assert!(MessageType::from_u8(0x00).is_none());
    }
}
