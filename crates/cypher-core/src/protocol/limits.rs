//! Per-type decoded payload ceilings and frame-size bounds
//! (bitchat's `PacketPayloadLimits` + `FileTransferLimits`, exact values).

use crate::types::MessageType;

/// Raw DEFLATE's hard expansion ceiling.
pub const MAX_DEFLATE_RATIO: usize = 1032;

const FRAGMENT_BYTES: usize = 1_280;
const CONTROL_BYTES: usize = 4 * 1024;
const REQUEST_SYNC_BYTES: usize = 8 * 1024;
const ENVELOPE_BYTES: usize = 64 * 1024;
const TWICE_V1_FRAME_BYTES: usize = 128 * 1024;

/// Largest decoded payload accepted for a packet of `type`, compressed or not.
pub fn max_payload_bytes(packet_type: u8) -> usize {
    match MessageType::from_u8(packet_type) {
        // Unknown types still relay, capped at 128 KiB.
        None => TWICE_V1_FRAME_BYTES,
        Some(MessageType::FileTransfer) | Some(MessageType::NoiseEncrypted) => {
            crate::consts::FILE_MAX_FRAMED_BYTES
        }
        Some(MessageType::Message) | Some(MessageType::GroupMessage) => TWICE_V1_FRAME_BYTES,
        Some(MessageType::CourierEnvelope) | Some(MessageType::NostrCarrier) => ENVELOPE_BYTES,
        Some(MessageType::RequestSync) => REQUEST_SYNC_BYTES,
        Some(MessageType::Fragment) => FRAGMENT_BYTES,
        // announce, announceV2, leave, boardPost, prekeyBundle, voiceFrame,
        // noiseHandshake, ping, pong
        Some(_) => CONTROL_BYTES,
    }
}

/// Everything a frame can carry besides its payload: v2 header, sender,
/// recipient, widest accepted route (255 hops), compressed-size field,
/// signature, and the most padding `MessagePadding` adds.
pub const MAX_FRAME_OVERHEAD_BYTES: usize = 16 + 8 + 8 + 1 + 255 * 8 + 4 + 64 + 255; // 2,396

/// Largest encoded frame (padding included) that can still decode as a packet
/// of `type`. Fragment reassembly holds assemblies to this bound.
pub fn max_frame_bytes(packet_type: u8) -> usize {
    max_payload_bytes(packet_type) + MAX_FRAME_OVERHEAD_BYTES
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn caps_match_bitchat() {
        assert_eq!(max_payload_bytes(0x22), 1_179_760); // fileTransfer
        assert_eq!(max_payload_bytes(0x11), 1_179_760); // noiseEncrypted
        assert_eq!(max_payload_bytes(0x02), 131_072); // message
        assert_eq!(max_payload_bytes(0x25), 131_072); // groupMessage
        assert_eq!(max_payload_bytes(0xEE), 131_072); // unknown
        assert_eq!(max_payload_bytes(0x04), 65_536); // courierEnvelope
        assert_eq!(max_payload_bytes(0x28), 65_536); // nostrCarrier
        assert_eq!(max_payload_bytes(0x21), 8_192); // requestSync
        assert_eq!(max_payload_bytes(0x20), 1_280); // fragment
        assert_eq!(max_payload_bytes(0x01), 4_096); // announce
        assert_eq!(max_payload_bytes(0x10), 4_096); // noiseHandshake
        assert_eq!(max_payload_bytes(0x26), 4_096); // ping
        assert_eq!(max_frame_bytes(0x20), 1_280 + 2_396);
    }
}
