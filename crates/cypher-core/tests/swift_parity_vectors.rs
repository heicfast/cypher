//! Cross-language parity vectors — every value here was pinned by the
//! bitchat Swift test suite (bitchatTests + BitFoundationTests) and
//! independently re-derived. If any assertion in this file fails, the Rust
//! port has diverged from the Swift implementation on the wire.

use cypher_core::packet::{mesh_message_identity, BinaryProtocol, BitchatPacket};
use cypher_core::peer_id::PeerId;
use cypher_core::protocol::announce::AuthenticatedPeerStatePacket;
use cypher_core::protocol::capabilities::PeerCapabilities;
use cypher_core::protocol::courier::CourierEnvelope;
use cypher_core::protocol::file::PrivateMediaMessageIdentity;
use cypher_core::protocol::private::{MeshPingPayload, PrivateMessagePacket};
use cypher_core::protocol::sync::{GcsFilter, SyncTypeFlags};
use cypher_core::util;

// ---------------------------------------------------------------------------
// 1. AuthenticatedPeerStatePacket — canonical TLV bytes
// (PacketsTests.swift:150-157)
// ---------------------------------------------------------------------------
#[test]
fn authenticated_peer_state_canonical_bytes() {
    // capabilities = privateMedia(1<<8) | vouch(1<<5) = 0x0120
    let packet = AuthenticatedPeerStatePacket {
        capabilities: PeerCapabilities(PeerCapabilities::PRIVATE_MEDIA | PeerCapabilities::VOUCH),
        signing_public_key: vec![0xA5; 32],
    };
    let encoded = packet.encode().expect("encodes");
    // version 0x01, TLV 0x01 (caps), len 0x02, minimal LE [0x20, 0x01]
    assert_eq!(&encoded[..5], &[0x01, 0x01, 0x02, 0x20, 0x01]);
    assert_eq!(&encoded[5..7], &[0x02, 0x20]); // TLV 0x02 (signing), len 32
}

#[test]
fn authenticated_peer_state_rejections() {
    let key = [0xA5u8; 32].to_vec();
    let caps = PeerCapabilities(PeerCapabilities::PRIVATE_MEDIA);
    let good = AuthenticatedPeerStatePacket {
        capabilities: caps,
        signing_public_key: key.clone(),
    }
    .encode()
    .unwrap();
    let caps_tlv = good[1..5].to_vec(); // 0x01 TLV: [01 02 20 01]
    let signing_tlv: Vec<u8> = {
        let mut v = vec![0x02, 0x20];
        v.extend_from_slice(&key);
        v
    };
    let join = |parts: &[&[u8]]| -> Vec<u8> {
        let mut out = vec![0x01u8];
        for p in parts {
            out.extend_from_slice(p);
        }
        out
    };
    // Unknown version.
    assert!(
        AuthenticatedPeerStatePacket::decode(&join(&[&[0x02], &caps_tlv, &signing_tlv])).is_none()
    );
    // Missing caps.
    assert!(AuthenticatedPeerStatePacket::decode(&join(&[&signing_tlv])).is_none());
    // Duplicate caps.
    assert!(
        AuthenticatedPeerStatePacket::decode(&join(&[&caps_tlv, &caps_tlv, &signing_tlv]))
            .is_none()
    );
    // Non-minimal LE capabilities ([0x01, 0x00] decodes to 1, re-encodes []).
    let non_minimal: Vec<u8> = {
        let mut v = vec![0x01, 0x02, 0x01, 0x00];
        v.extend_from_slice(&signing_tlv);
        v
    };
    assert!(AuthenticatedPeerStatePacket::decode(&join(&[&non_minimal])).is_none());
    // Key of 31 bytes.
    let short_key_tlv: Vec<u8> = {
        let mut v = vec![0x02, 0x1F];
        v.extend_from_slice(&key[..31]);
        v
    };
    assert!(AuthenticatedPeerStatePacket::decode(&join(&[&caps_tlv, &short_key_tlv])).is_none());
}

// ---------------------------------------------------------------------------
// 2. PrivateMessagePacket rejections (PacketsTests.swift:182-188)
// ---------------------------------------------------------------------------
#[test]
fn private_message_packet_rejections() {
    // Unknown TLV type 0x7F.
    assert!(PrivateMessagePacket::decode(&[0x7F, 0x01, 0x41]).is_none());
    // Declares 5 bytes, supplies 1.
    assert!(PrivateMessagePacket::decode(&[0x00, 0x05, 0x61]).is_none());
    // Canonical round-trip: messageID TLV 0x00, content TLV 0x01 (u8 lens).
    let pm = PrivateMessagePacket {
        message_id: "m1".into(),
        content: "hello".into(),
    };
    let encoded = pm.encode().expect("encodes");
    assert_eq!(
        encoded,
        vec![0x00, 0x02, b'm', b'1', 0x01, 0x05, b'h', b'e', b'l', b'l', b'o']
    );
    assert_eq!(PrivateMessagePacket::decode(&encoded).as_ref(), Some(&pm));
}

// ---------------------------------------------------------------------------
// 3. BinaryProtocol malformed-frame rejections (BinaryProtocolTests.swift)
// ---------------------------------------------------------------------------
#[test]
fn malformed_frames_rejected() {
    // (a) 30 B total claiming a 193-B payload.
    let a: Vec<u8> = [
        &[0x01u8, 0x01, 0x0A, 0, 0, 0, 0, 0, 0, 0, 0, 0x00, 0x00, 0xC1][..],
        &[1u8; 8][..],
        &[2u8; 8][..],
    ]
    .concat();
    assert!(BinaryProtocol::decode(&a).is_none());
    // (b) compressed with payloadLen=1 < 2-byte orig-size prefix.
    let b: Vec<u8> = [
        &[0x01u8, 0x01, 0x0A, 0, 0, 0, 0, 0, 0, 0, 0, 0x04, 0x00, 0x01][..],
        &[1u8; 8][..],
        &[0x99u8][..],
    ]
    .concat();
    assert!(BinaryProtocol::decode(&b).is_none());
    // (c) claims 65535 with 3 payload bytes.
    let c: Vec<u8> = [
        &[0x01u8, 0x01, 0x0A, 0, 0, 0, 0, 0, 0, 0, 0, 0x00, 0xFF, 0xFF][..],
        &[1u8; 8][..],
        &[0x01, 0x02, 0x03][..],
    ]
    .concat();
    assert!(BinaryProtocol::decode(&c).is_none());
    // (d) 5 bytes only.
    assert!(BinaryProtocol::decode(&[0u8; 5]).is_none());
    // (e) unknown version 0xFF on a valid frame.
    let minimal: Vec<u8> = [
        &[0x01u8, 0x01, 0x0A, 0, 0, 0, 0, 0, 0, 0, 0, 0x00, 0x00, 0x00][..],
        &[1u8; 8][..],
    ]
    .concat();
    let mut e = minimal.clone();
    e[0] = 0xFF;
    assert!(BinaryProtocol::decode(&e).is_none());
    // (f) the minimal 22-byte frame decodes without crashing.
    assert!(BinaryProtocol::decode(&minimal).is_some());
}

// ---------------------------------------------------------------------------
// 4. Voice burst wire bytes (VoiceBurstPacketTests.swift:14-56, 60-79)
// ---------------------------------------------------------------------------
#[test]
fn voice_burst_wire_vectors() {
    use cypher_core::protocol::voice::{
        VoiceBurstPacket, CODEC_AAC_LC, FLAG_CANCELED, FLAG_DATA, FLAG_END, FLAG_START,
    };
    let burst = [1u8, 2, 3, 4, 5, 6, 7, 8];
    // START: 0102030405060708 0000 01 01
    let start = VoiceBurstPacket::start(burst, CODEC_AAC_LC);
    let start_wire = start.encode().unwrap();
    assert_eq!(
        &start_wire,
        &[0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x00, 0x00, 0x01, 0x01][..]
    );
    // FRAMES (seq 7): …0007 00 0002 DEAD 0082 42×130
    let frames = VoiceBurstPacket::data(burst, 7, &[vec![0xDE, 0xAD], vec![0x42; 130]]).unwrap();
    let frames_wire = frames.encode().unwrap();
    assert_eq!(&frames_wire[8..11], &[0x00, 0x07, 0x00][..]);
    assert_eq!(&frames_wire[11..15], &[0x00, 0x02, 0xDE, 0xAD][..]);
    assert_eq!(&frames_wire[15..17], &[0x00, 0x82][..]);
    assert_eq!(frames_wire[17], 0x42);
    // END (seq 42, total 41, duration 2688 = 0x00000A80): …002A 02 0029 00000A80
    let mut end = VoiceBurstPacket::end(burst, 41, 2688);
    end.seq = 42; // Swift fixture pins seq 42 on the END packet
    let end_wire = end.encode().unwrap();
    assert_eq!(
        &end_wire[8..],
        &[0x00, 0x2A, 0x02, 0x00, 0x29, 0x00, 0x00, 0x0A, 0x80][..]
    );
    // CANCELED (seq 3): …0003 04
    let mut canceled = VoiceBurstPacket::canceled(burst);
    canceled.seq = 3;
    let canceled_wire = canceled.encode().unwrap();
    assert_eq!(&canceled_wire[8..], &[0x00, 0x03, 0x04][..]);
    // Malformed decodes.
    let base = [0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08];
    assert!(VoiceBurstPacket::decode(&[]).is_none());
    assert!(VoiceBurstPacket::decode(&[0u8; 10]).is_none());
    assert!(VoiceBurstPacket::decode(&[&base[..], &[0, 1, 0xFF][..]].concat()).is_none()); // unknown flags
    assert!(VoiceBurstPacket::decode(&[&base[..], &[0, 1, 0][..]].concat()).is_none()); // zero frames
                                                                                        // Truncated frame length.
    assert!(
        VoiceBurstPacket::decode(&[&base[..], &[0, 1, 0, 0x00, 0x10, 0xAB][..]].concat()).is_none()
    );
    // START with unknown codec.
    assert!(VoiceBurstPacket::decode(&[&base[..], &[0, 0, 0x01, 0x7F][..]].concat()).is_none());
    // Flags constants pinned.
    assert_eq!(
        (FLAG_START, FLAG_END, FLAG_CANCELED, FLAG_DATA),
        (0x01, 0x02, 0x04, 0x00)
    );
}

// ---------------------------------------------------------------------------
// 5. File packets — legacy 8-byte fileSize, content-only fallback
// (BitchatFilePacketTests.swift:49-76)
// ---------------------------------------------------------------------------
#[test]
fn file_packet_legacy_tlv_vectors() {
    use cypher_core::protocol::file::BitchatFilePacket;
    // fileName + mimeType TLVs first (the decoder requires them), then the
    // legacy [0x02][u16 len=8][u64 BE 256] + content TLV [0x04][u32 len=4].
    let mut data: Vec<u8> = vec![0x01, 0x00, 0x09];
    data.extend_from_slice(b"photo.jpg");
    data.extend_from_slice(&[0x03, 0x00, 0x0A]);
    data.extend_from_slice(b"image/jpeg");
    data.extend_from_slice(&[0x02, 0x00, 0x08]);
    data.extend_from_slice(&[0, 0, 0, 0, 0, 0, 0x01, 0x00]); // 256 as u64 BE
    data.extend_from_slice(&[0x04, 0, 0, 0, 0x04]);
    data.extend_from_slice(&[0x01, 0x02, 0x03, 0x04]);
    let decoded = BitchatFilePacket::decode(&data).expect("legacy accepted");
    assert_eq!(decoded.file_size, 256);
    assert_eq!(decoded.content, vec![0x01, 0x02, 0x03, 0x04]);
    // Content-only: fileSize falls back to content length.
    let only: Vec<u8> = [
        &[0x01u8, 0x00, 0x09][..],
        b"photo.jpg".as_ref(),
        &[0x03, 0x00, 0x0A][..],
        b"image/jpeg".as_ref(),
        &[0x04, 0, 0, 0, 0x03][..],
        &[0xAA, 0xBB, 0xCC][..],
    ]
    .concat();
    let decoded = BitchatFilePacket::decode(&only).expect("content-only accepted");
    assert_eq!(decoded.file_size, 3);
    assert_eq!(decoded.content, vec![0xAA, 0xBB, 0xCC]);
}

// ---------------------------------------------------------------------------
// 6. Stable media ID golden (BitchatFilePacketTests.swift:212-221)
// ---------------------------------------------------------------------------
#[test]
fn stable_media_id_golden() {
    assert_eq!(
        PrivateMediaMessageIdentity::stable_id(
            "0011223344556677",
            "8899aabbccddeeff",
            "img_20260725_105708_1CC2760D-76AA-40C3-8013-C7FAA6C2EF99.jpg"
        )
        .as_deref(),
        Some("media-910bd42c65060ab76bb6406f220c4516")
    );
    // Direction-sensitive.
    assert_ne!(
        PrivateMediaMessageIdentity::stable_id(
            "8899aabbccddeeff",
            "0011223344556677",
            "img_20260725_105708_1CC2760D-76AA-40C3-8013-C7FAA6C2EF99.jpg"
        ),
        PrivateMediaMessageIdentity::stable_id(
            "0011223344556677",
            "8899aabbccddeeff",
            "img_20260725_105708_1CC2760D-76AA-40C3-8013-C7FAA6C2EF99.jpg"
        )
    );
}

// ---------------------------------------------------------------------------
// 7. Mesh message identity golden (BridgeWireFormatTests.swift:116-131)
// ---------------------------------------------------------------------------
#[test]
fn mesh_message_identity_golden() {
    assert_eq!(
        mesh_message_identity("0011223344556677", 1_750_000_000_123, "hello mesh"),
        "b83f94d81dcdd1b0c0048f6645995dd4"
    );
    // Lowercases the sender; trims the content.
    assert_eq!(
        mesh_message_identity("0011223344ABCD", 1, " hi \n"),
        mesh_message_identity("0011223344abcd", 1, "hi")
    );
}

// ---------------------------------------------------------------------------
// 8. PeerID short fingerprints (PeerIDTests.swift:198-213, verified)
// ---------------------------------------------------------------------------
#[test]
fn peer_id_short_fingerprint_goldens() {
    assert_eq!(
        &PeerId::fingerprint_of(&[0xAB; 32])[..16],
        "9a2db2e23f1504cd"
    );
    assert_eq!(
        &PeerId::fingerprint_of(&[0xCD; 32])[..16],
        "7969ec0fcb8b648d"
    );
    assert_eq!(
        &PeerId::fingerprint_of(&[0x12; 32])[..16],
        "b6acca81a0939a85"
    );
}

// ---------------------------------------------------------------------------
// 9. Courier recipient tag + epoch day (CourierEnvelope.swift:169-193)
// ---------------------------------------------------------------------------
#[test]
fn courier_recipient_tag_golden() {
    // key 0x11×32, day 20000 → HMAC-SHA256(key, "bitchat-courier-tag-v1"
    // ‖ u32be(20000))[0..16]
    assert_eq!(CourierEnvelope::epoch_day(86_400 * 20_000), 20_000);
    let tag = CourierEnvelope::recipient_tag(&[0x11; 32], 20_000);
    assert_eq!(util::hex_encode(&tag), "173d54ec4ce3de7b45d355eea6500dde");
}

// ---------------------------------------------------------------------------
// 10. Sync type flags wire bytes (SyncTypeFlagsTests / Board/Group variants)
// ---------------------------------------------------------------------------
#[test]
fn sync_type_flags_wire_vectors() {
    // Board → [0x00, 0x01] (LE u64, trailing zeros trimmed).
    let board = SyncTypeFlags(1 << 8);
    assert_eq!(board.encode(), vec![0x00, 0x01]);
    // announce+message → [0x03]; +groupMessage → [0x03, 0x04].
    let pub_msgs = SyncTypeFlags((1 << 0) | (1 << 1));
    assert_eq!(pub_msgs.encode(), vec![0x03]);
    let with_group = SyncTypeFlags((1 << 0) | (1 << 1) | (1 << 10));
    assert_eq!(with_group.encode(), vec![0x03, 0x04]);
    // Phantom bits (11+) are discarded: [0x00, 0xF8] → 0.
    assert_eq!(SyncTypeFlags::decode(&[0x00, 0xF8]).0, 0);
    // [0x00, 0xF9] → boardPost only.
    assert_eq!(SyncTypeFlags::decode(&[0x00, 0xF9]).0, 1 << 8);
    // [0x03, 0xFC] → announce+message+groupMessage.
    assert_eq!(
        SyncTypeFlags::decode(&[0x03, 0xFC]).0,
        (1 << 0) | (1 << 1) | (1 << 10)
    );
    // All 64 bits set trim to exactly FF 07 (known mask 0x7FF).
    assert_eq!(SyncTypeFlags(u64::MAX).encode(), vec![0xFF, 0x07]);
    // Legacy 1-byte form.
    assert_eq!(SyncTypeFlags::decode(&[0x03]).0, (1 << 0) | (1 << 1));
}

// ---------------------------------------------------------------------------
// 11. GCS filter parameter validation (GCSFilterTests.swift:62-68)
// ---------------------------------------------------------------------------
#[test]
fn gcs_parameter_validation() {
    // p must be 1..=32 (GCSFilter.maxP); 200 and 0 produce nothing.
    assert_eq!(GcsFilter::encode_ids(&[[1u8; 16]], 200), (Vec::new(), 0));
    assert_eq!(GcsFilter::encode_ids(&[[1u8; 16]], 0), (Vec::new(), 0));
    assert!(!GcsFilter::encode_ids(&[[1u8; 16]], 1).0.is_empty());
    assert!(!GcsFilter::encode_ids(&[[1u8; 16]], 32).0.is_empty());
    // Decode-side guards (m in {0, 1, u32::MAX} rejected too).
    let (bits, m) = GcsFilter::encode_ids(&[[1u8; 16], [2u8; 16]], 8);
    assert!(GcsFilter::decode_ids(&bits, 8, 0).is_empty());
    assert!(GcsFilter::decode_ids(&bits, 8, 1).is_empty());
    assert!(!GcsFilter::decode_ids(&bits, 8, m).is_empty());
    // Zero-bucket remap: bucket never 0.
    for m in [2u32, 3, 5, 97] {
        assert_ne!(GcsFilter::bucket(&[1u8; 16], m), 0);
    }
}

// ---------------------------------------------------------------------------
// 12. Mesh ping payload wire bytes (MeshPingPayloadTests.swift:15-36)
// ---------------------------------------------------------------------------
#[test]
fn mesh_ping_wire_bytes() {
    let nonce = [0x01u8, 0x02, 0x03, 0x04, 0xAA, 0xBB, 0xCC, 0xFF];
    let ping = MeshPingPayload::new(nonce, 7);
    let wire = ping.encode();
    assert_eq!(wire.len(), 9);
    assert_eq!(&wire[..8], &nonce);
    assert_eq!(wire[8], 7);
    // Trailing bytes tolerated; short input rejected.
    assert!(MeshPingPayload::decode(&[&wire[..], &[0xDE, 0xAD][..]].concat()).is_some());
    assert!(MeshPingPayload::decode(&wire[..8]).is_none());
    // hopCount arithmetic.
    assert_eq!(MeshPingPayload::hop_count(7, 7), Some(1));
    assert_eq!(MeshPingPayload::hop_count(7, 6), Some(2));
    assert_eq!(MeshPingPayload::hop_count(7, 1), Some(7));
    assert_eq!(MeshPingPayload::hop_count(3, 7), None);
}

// ---------------------------------------------------------------------------
// 13. Packet-level: v2 vs v1 frame delta with one route hop
// (BinaryProtocolTests.swift:247-291)
// ---------------------------------------------------------------------------
#[test]
fn v2_v1_frame_delta_with_route() {
    use cypher_core::types::MessageType;
    let sender = PeerId::new("0011223344556677");
    let mut v1 = BitchatPacket::new(
        MessageType::Message.as_u8(),
        sender.routing_bytes(),
        None,
        1_000,
        vec![0x41; 32],
        7,
    );
    v1.route = Some(vec![[9u8; 8]]);
    let v1_wire = v1.to_binary_data(false).expect("v1 encodes");
    let mut v2 = v1.clone();
    v2.version = 2;
    let v2_wire = v2.to_binary_data(false).expect("v2 encodes");
    // Header 14→16 (+2), route count byte (+1), one 8-B hop (+8) = 11.
    assert_eq!(v2_wire.len() - v1_wire.len(), 11);
    // v1 cannot carry a route: the hasRoute flag must be clear.
    let flags_v1 = v1_wire[11];
    assert_eq!(flags_v1 & 0x08, 0);
    let flags_v2 = v2_wire[11];
    assert_ne!(flags_v2 & 0x08, 0);
}
