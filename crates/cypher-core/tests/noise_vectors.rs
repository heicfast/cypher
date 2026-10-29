//! Golden-vector validation against bitchat's `NoiseTestVectors.json`
//! (the cacophony and snow `Noise_XX_25519_ChaChaPoly_SHA256` vectors).
//!
//! These prove byte-exact compatibility of the Noise handshake and transport
//! encryption with the reference implementation.

use cypher_core::noise::handshake::{HandshakeState, NoisePattern, NoiseRole};
use cypher_core::noise::NoiseError;
use x25519_dalek::StaticSecret;

#[derive(serde::Deserialize)]
struct VectorMessage {
    payload: String,
    ciphertext: String,
}

#[derive(serde::Deserialize)]
struct Vector {
    protocol_name: String,
    init_prologue: String,
    init_static: String,
    init_ephemeral: String,
    resp_prologue: String,
    resp_static: String,
    resp_ephemeral: String,
    #[serde(default)]
    handshake_hash: Option<String>,
    messages: Vec<VectorMessage>,
}

fn key(hex_str: &str) -> StaticSecret {
    let bytes = cypher_core::util::hex_decode(hex_str).expect("valid hex key");
    let mut seed = [0u8; 32];
    seed.copy_from_slice(&bytes);
    StaticSecret::from(seed)
}

#[test]
fn noise_xx_golden_vectors() {
    let data = std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/fixtures/NoiseTestVectors.json"
    ))
    .expect("fixtures present");
    let vectors: Vec<Vector> = serde_json::from_str(&data).expect("valid JSON");

    for (vi, v) in vectors.iter().enumerate() {
        assert_eq!(v.protocol_name, "Noise_XX_25519_ChaChaPoly_SHA256");

        let mut initiator = HandshakeState::with_predetermined_ephemeral(
            NoiseRole::Initiator,
            NoisePattern::Xx,
            Some(key(&v.init_static)),
            None,
            &cypher_core::util::hex_decode(&v.init_prologue).unwrap(),
            Some(key(&v.init_ephemeral)),
        );
        let mut responder = HandshakeState::with_predetermined_ephemeral(
            NoiseRole::Responder,
            NoisePattern::Xx,
            Some(key(&v.resp_static)),
            None,
            &cypher_core::util::hex_decode(&v.resp_prologue).unwrap(),
            Some(key(&v.resp_ephemeral)),
        );

        let n = v.messages.len();
        assert!(n >= 3, "vector {vi} must include the 3 handshake messages");

        // Handshake: msg0 I->R, msg1 R->I, msg2 I->R.
        let m0 = initiator
            .write_message(&cypher_core::util::hex_decode(&v.messages[0].payload).unwrap())
            .unwrap();
        assert_eq!(
            cypher_core::util::hex_encode(&m0),
            v.messages[0].ciphertext,
            "vector {vi} handshake msg 0 mismatch"
        );

        let m1_payload = responder
            .read_message(&m0)
            .expect("responder reads msg 0");
        assert_eq!(
            cypher_core::util::hex_encode(&m1_payload),
            v.messages[0].payload,
            "vector {vi} msg 0 payload mismatch"
        );
        let m1 = responder
            .write_message(&cypher_core::util::hex_decode(&v.messages[1].payload).unwrap())
            .unwrap();
        assert_eq!(
            cypher_core::util::hex_encode(&m1),
            v.messages[1].ciphertext,
            "vector {vi} handshake msg 1 mismatch"
        );

        let m2_payload = initiator
            .read_message(&m1)
            .expect("initiator reads msg 1");
        assert_eq!(
            cypher_core::util::hex_encode(&m2_payload),
            v.messages[1].payload,
            "vector {vi} msg 1 payload mismatch"
        );
        let m2 = initiator
            .write_message(&cypher_core::util::hex_decode(&v.messages[2].payload).unwrap())
            .unwrap();
        assert_eq!(
            cypher_core::util::hex_encode(&m2),
            v.messages[2].ciphertext,
            "vector {vi} handshake msg 2 mismatch"
        );
        let m2_out_payload = responder
            .read_message(&m2)
            .expect("responder reads msg 2");
        assert_eq!(
            cypher_core::util::hex_encode(&m2_out_payload),
            v.messages[2].payload,
            "vector {vi} msg 2 payload mismatch"
        );

        assert!(initiator.is_handshake_complete());
        assert!(responder.is_handshake_complete());

        if let Some(expected_hash) = &v.handshake_hash {
            assert_eq!(
                cypher_core::util::hex_encode(&initiator.get_handshake_hash()),
                *expected_hash,
                "vector {vi} handshake hash mismatch"
            );
        }

        // Transport messages: standard Noise counters (no extracted nonce —
        // the vectors use implicit nonces), responder speaks first after the
        // handshake. msg3: R->I, msg4: I->R, msg5: R->I, ...
        let (mut i_send, mut i_recv, _) = initiator
            .get_transport_ciphers(false)
            .expect("initiator ciphers");
        let (mut r_send, mut r_recv, _) = responder
            .get_transport_ciphers(false)
            .expect("responder ciphers");

        for (mi, m) in v.messages.iter().enumerate().skip(3) {
            let payload = cypher_core::util::hex_decode(&m.payload).unwrap();
            if mi % 2 == 1 {
                // Responder -> Initiator (odd original indexes 3, 5, ...)
                let ct = r_send.encrypt(&payload, &[]).unwrap();
                assert_eq!(
                    cypher_core::util::hex_encode(&ct),
                    m.ciphertext,
                    "vector {vi} transport msg {mi} mismatch"
                );
                let pt = i_recv.decrypt(&ct, &[]).unwrap();
                assert_eq!(pt, payload);
            } else {
                // Initiator -> Responder (even original indexes 4, 6, ...)
                let ct = i_send.encrypt(&payload, &[]).unwrap();
                assert_eq!(
                    cypher_core::util::hex_encode(&ct),
                    m.ciphertext,
                    "vector {vi} transport msg {mi} mismatch"
                );
                let pt = r_recv.decrypt(&ct, &[]).unwrap();
                assert_eq!(pt, payload);
            }
        }
    }
}

#[test]
fn xx_message_sizes_match_bitchat() {
    // With empty payloads: 32 / 96 / 64 bytes (bitchat's constants).
    let alice = StaticSecret::from([0xA1; 32]);
    let bob = StaticSecret::from([0xB2; 32]);
    let mut i = HandshakeState::new(NoiseRole::Initiator, NoisePattern::Xx, Some(alice), None, &[]);
    let mut r = HandshakeState::new(NoiseRole::Responder, NoisePattern::Xx, Some(bob), None, &[]);
    let m1 = i.write_message(&[]).unwrap();
    assert_eq!(m1.len(), cypher_core::consts::NOISE_XX_INITIAL_MESSAGE_SIZE);
    assert_eq!(r.read_message(&m1).unwrap().len(), 0);
    let m2 = r.write_message(&[]).unwrap();
    assert_eq!(m2.len(), 96);
    assert_eq!(i.read_message(&m2).unwrap().len(), 0);
    let m3 = i.write_message(&[]).unwrap();
    assert_eq!(m3.len(), 64);
    assert_eq!(r.read_message(&m3).unwrap().len(), 0);
}

#[test]
fn tampered_handshake_fails_closed() {
    let alice = StaticSecret::from([0xA1; 32]);
    let bob = StaticSecret::from([0xB2; 32]);
    let mut i = HandshakeState::new(NoiseRole::Initiator, NoisePattern::Xx, Some(alice), None, &[]);
    let mut r = HandshakeState::new(NoiseRole::Responder, NoisePattern::Xx, Some(bob), None, &[]);

    // Message 1 is unauthenticated by design (bare ephemeral): a corrupted
    // but well-formed ephemeral still parses — authentication binds later.
    let m1 = i.write_message(&[]).unwrap();
    assert!(r.read_message(&m1).is_ok());
    let m2 = r.write_message(&[]).unwrap();

    // Corrupting message 2 (which carries the encrypted static) must fail
    // closed at the initiator with an authentication failure.
    let mut bad_m2 = m2.clone();
    bad_m2[40] ^= 0xFF;
    assert!(matches!(
        i.read_message(&bad_m2),
        Err(NoiseError::AuthenticationFailure)
    ));

    // A blacklisted (low-order) ephemeral in message 1 is rejected outright.
    let bob2 = StaticSecret::from([0xB2; 32]);
    let mut r2 = HandshakeState::new(NoiseRole::Responder, NoisePattern::Xx, Some(bob2), None, &[]);
    let mut evil = m1.clone();
    evil[..32].copy_from_slice(&[0xFF; 32]);
    assert!(matches!(
        r2.read_message(&evil),
        Err(NoiseError::InvalidMessage)
    ));
}
