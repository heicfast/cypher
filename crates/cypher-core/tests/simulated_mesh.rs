//! Simulated mesh: two engines wired link-to-link through the
//! LinkCommand/LinkEvent boundary — the same seam the Android BLE layer
//! will use. Proves announce exchange, link binding, Noise XX session
//! establishment, public broadcast delivery, private delivery, and
//! duplicate suppression, deterministically under a manual clock.

use cypher_core::identity::CypherIdentity;
use cypher_core::mesh::engine::{EngineOutput, MeshEngine};
use cypher_core::mesh::{EngineEvent, LinkCommand, LinkDirection, LinkEvent, RadioState};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

/// One shared wall clock for the whole simulation.
struct Clock(Arc<AtomicU64>);

impl Clock {
    fn new(start: u64) -> Self {
        Clock(Arc::new(AtomicU64::new(start)))
    }
    #[allow(dead_code)]
    fn now(&self) -> u64 {
        self.0.load(Ordering::SeqCst)
    }
    fn advance(&self, ms: u64) -> u64 {
        self.0.fetch_add(ms, Ordering::SeqCst) + ms
    }
    fn boxed(&self) -> Box<dyn Fn() -> u64 + Send + Sync> {
        let cell = self.0.clone();
        Box::new(move || cell.load(Ordering::SeqCst))
    }
}

/// A node: engine + collected UI events, on a single simulated link (id 1).
struct Node {
    engine: MeshEngine,
    #[allow(dead_code)]
    name: String,
    events: Vec<EngineEvent>,
}

struct World {
    alice: Node,
    bob: Node,
    clock: Clock,
}

impl World {
    fn new(start_ms: u64) -> Self {
        let clock = Clock::new(start_ms);
        let make = |name: &str| -> Node {
            let identity = CypherIdentity::generate();
            Node {
                engine: MeshEngine::with_clock(identity, name.to_string(), clock.boxed()),
                name: name.to_string(),
                events: Vec::new(),
            }
        };
        World {
            alice: make("alice"),
            bob: make("bob"),
            clock,
        }
    }

    /// Bring both radios up and both links up, cross-delivering everything.
    fn bring_up(&mut self) {
        // Radios on.
        for node in [&mut self.alice, &mut self.bob] {
            let out = node
                .engine
                .handle(LinkEvent::RadioStateChanged(RadioState::PoweredOn));
            node.events.extend(out.events);
        }
        // Links up (each node's engine sees its own link id 1).
        let mut commands = Vec::new();
        for node in [&mut self.alice, &mut self.bob] {
            let out = node.engine.handle(LinkEvent::LinkUp {
                link: 1,
                direction: LinkDirection::Central,
            });
            node.events.extend(out.events);
            commands.extend(out.commands);
        }
        // Cross-deliver the announces both engines sent on link-up.
        self.deliver_alice_to_bob(&commands.clone());
        self.deliver_bob_to_alice(&commands);
    }

    fn deliver_alice_to_bob(&mut self, commands: &[LinkCommand]) {
        // Full-duplex pump: feed alice's writes to bob, feed bob's response
        // writes back to alice, until quiescent (bounded).
        let mut to_bob: Vec<Vec<u8>> = commands
            .iter()
            .filter_map(|c| match c {
                LinkCommand::Write { bytes, .. } => Some(bytes.clone()),
                _ => None,
            })
            .collect();
        for _ in 0..12 {
            if to_bob.is_empty() {
                break;
            }
            let mut to_alice = Vec::new();
            for bytes in to_bob.drain(..) {
                let out = self.bob.engine.handle(LinkEvent::BytesReceived {
                    link: 1,
                    direction: LinkDirection::Central,
                    bytes,
                });
                self.bob.events.extend(out.events);
                to_alice.extend(out.commands.into_iter().filter_map(|c| match c {
                    LinkCommand::Write { bytes, .. } => Some(bytes),
                    _ => None,
                }));
            }
            if to_alice.is_empty() {
                break;
            }
            let mut back = Vec::new();
            for bytes in to_alice {
                let out = self.alice.engine.handle(LinkEvent::BytesReceived {
                    link: 1,
                    direction: LinkDirection::Central,
                    bytes,
                });
                self.alice.events.extend(out.events);
                back.extend(out.commands.into_iter().filter_map(|c| match c {
                    LinkCommand::Write { bytes, .. } => Some(bytes),
                    _ => None,
                }));
            }
            to_bob = back;
        }
    }

    fn deliver_bob_to_alice(&mut self, commands: &[LinkCommand]) {
        // Same pump starting from bob's writes.
        let mut to_alice: Vec<Vec<u8>> = commands
            .iter()
            .filter_map(|c| match c {
                LinkCommand::Write { bytes, .. } => Some(bytes.clone()),
                _ => None,
            })
            .collect();
        for _ in 0..12 {
            if to_alice.is_empty() {
                break;
            }
            let mut to_bob = Vec::new();
            for bytes in to_alice.drain(..) {
                let out = self.alice.engine.handle(LinkEvent::BytesReceived {
                    link: 1,
                    direction: LinkDirection::Central,
                    bytes,
                });
                self.alice.events.extend(out.events);
                to_bob.extend(out.commands.into_iter().filter_map(|c| match c {
                    LinkCommand::Write { bytes, .. } => Some(bytes),
                    _ => None,
                }));
            }
            if to_bob.is_empty() {
                break;
            }
            let mut back = Vec::new();
            for bytes in to_bob {
                let out = self.bob.engine.handle(LinkEvent::BytesReceived {
                    link: 1,
                    direction: LinkDirection::Central,
                    bytes,
                });
                self.bob.events.extend(out.events);
                back.extend(out.commands.into_iter().filter_map(|c| match c {
                    LinkCommand::Write { bytes, .. } => Some(bytes),
                    _ => None,
                }));
            }
            to_alice = back;
        }
    }

    /// Run both engines' ticks with advancing time, cross-delivering writes,
    /// until nothing new happens (or rounds run out).
    fn settle(&mut self, rounds: usize) {
        for _ in 0..rounds {
            self.clock.advance(50);
            let out_a: EngineOutput = self.alice.engine.tick();
            self.alice.events.extend(out_a.events);
            self.deliver_alice_to_bob(&out_a.commands);

            let out_b: EngineOutput = self.bob.engine.tick();
            self.bob.events.extend(out_b.events);
            self.deliver_bob_to_alice(&out_b.commands);
        }
    }

    fn alice_sends(&mut self, output: EngineOutput) {
        self.alice.events.extend(output.events);
        self.deliver_alice_to_bob(&output.commands);
    }

    fn bob_sends(&mut self, output: EngineOutput) {
        self.bob.events.extend(output.events);
        self.deliver_bob_to_alice(&output.commands);
    }
}

fn count(node: &Node, predicate: fn(&EngineEvent) -> bool) -> usize {
    node.events.iter().filter(|e| predicate(e)).count()
}

fn is_public_message(e: &EngineEvent) -> bool {
    matches!(e, EngineEvent::PublicMessageReceived { .. })
}
fn is_peer_connected(e: &EngineEvent) -> bool {
    matches!(e, EngineEvent::PeerConnected(_))
}
fn is_session_established(e: &EngineEvent) -> bool {
    matches!(e, EngineEvent::SessionEstablished { .. })
}
fn is_noise_payload(e: &EngineEvent) -> bool {
    matches!(e, EngineEvent::NoisePayloadReceived { .. })
}

#[test]
fn announce_exchange_binds_links_and_connects_peers() {
    let mut world = World::new(1_000_000);
    world.bring_up();
    world.settle(4);

    assert!(
        count(&world.alice, is_peer_connected) >= 1,
        "alice should see bob connect; events: {:?}",
        world.alice.events
    );
    assert!(
        count(&world.bob, is_peer_connected) >= 1,
        "bob should see alice connect; events: {:?}",
        world.bob.events
    );
    assert_eq!(world.alice.engine.connected_peers().len(), 1);
    assert_eq!(world.bob.engine.connected_peers().len(), 1);
    assert_eq!(
        world.alice.engine.connected_peers()[0],
        world.bob.engine.local_peer_id()
    );
}

#[test]
fn noise_session_establishes_and_private_message_delivers() {
    let mut world = World::new(2_000_000);
    world.bring_up();
    world.settle(4);

    // Alice sends a private message (handshake rides the same link).
    let out = world.alice.engine.send_private_message(
        &world.bob.engine.local_peer_id(),
        "the camp moved north",
        "dm-1",
    );
    world.alice_sends(out);
    world.settle(8);

    assert!(
        count(&world.bob, is_session_established) >= 1,
        "bob should establish a session; events: {:?}",
        world.bob.events
    );
    assert!(
        count(&world.alice, is_session_established) >= 1,
        "alice should establish a session"
    );
    assert!(
        count(&world.bob, is_noise_payload) >= 1,
        "bob should receive the private message payload"
    );

    // Round trip: Bob replies over the established session.
    let out = world.bob.engine.send_private_message(
        &world.alice.engine.local_peer_id(),
        "copy that",
        "dm-2",
    );
    world.bob_sends(out);
    assert!(
        count(&world.alice, is_noise_payload) >= 1,
        "alice should receive the reply"
    );
}

#[test]
fn public_message_broadcast_delivers_once() {
    let mut world = World::new(3_000_000);
    world.bring_up();
    world.settle(4);

    let out = world.alice.engine.send_public_message("hello mesh", &[]);
    let commands: Vec<LinkCommand> = out.commands.clone();
    world.alice_sends(out);

    // Bob delivers exactly once.
    assert_eq!(
        count(&world.bob, is_public_message),
        1,
        "delivered exactly once"
    );

    // Bob relays it back (echo): Alice must NOT re-deliver her own broadcast.
    world.settle(4);
    assert_eq!(
        count(&world.alice, is_public_message),
        0,
        "self-echo of own broadcast must not be delivered"
    );

    // A duplicate injection of the same wire bytes is suppressed.
    world.deliver_alice_to_bob(&commands);
    assert_eq!(
        count(&world.bob, is_public_message),
        1,
        "duplicates suppressed"
    );

    // Different content is a new message.
    let out = world.alice.engine.send_public_message("hello again", &[]);
    world.alice_sends(out);
    assert_eq!(count(&world.bob, is_public_message), 2);
}

#[test]
fn mesh_ping_measures_rtt() {
    let mut world = World::new(4_000_000);
    world.bring_up();
    world.settle(4);

    let out = world
        .alice
        .engine
        .send_mesh_ping(&world.bob.engine.local_peer_id());
    world.alice_sends(out);
    world.settle(4);

    let rtts: Vec<u64> = world
        .alice
        .events
        .iter()
        .filter_map(|e| match e {
            EngineEvent::MeshPingResult { rtt_ms, .. } => Some(*rtt_ms),
            _ => None,
        })
        .collect();
    assert_eq!(rtts.len(), 1, "exactly one ping result");
    assert!(
        rtts[0] < 500,
        "rtt within the simulated window, got {}",
        rtts[0]
    );
}

/// Connection-storm guard: duplicate `Discovered` events for one peripheral
/// (Android delivers scan hits continuously with duplicates allowed) must
/// produce exactly one `Connect`, and a live link must suppress reconnects.
#[test]
fn discovery_dedups_connection_attempts() {
    let clock = Clock::new(1_000_000);
    let identity = CypherIdentity::generate();
    let mut engine = MeshEngine::with_clock(identity, "alice".to_string(), clock.boxed());

    engine.handle(LinkEvent::RadioStateChanged(RadioState::PoweredOn));

    let discover = |engine: &mut MeshEngine, now: u64| {
        let out = engine.handle(LinkEvent::Discovered {
            peripheral: 7,
            rssi: -50,
            connectable: true,
        });
        let _ = now;
        out.commands
            .iter()
            .filter(|c| matches!(c, LinkCommand::Connect { peripheral: 7 }))
            .count()
    };

    // First discovery: connect.
    assert_eq!(
        discover(&mut engine, clock.now()),
        1,
        "first discovery connects"
    );

    // Repeated discoveries while the attempt is in flight: suppressed.
    clock.advance(1_000);
    assert_eq!(
        discover(&mut engine, clock.now()),
        0,
        "in-flight attempt suppresses duplicate connects"
    );
    clock.advance(1_000);
    assert_eq!(discover(&mut engine, clock.now()), 0);

    // Link established: the live link suppresses reconnects.
    engine.handle(LinkEvent::LinkUp {
        link: 7,
        direction: LinkDirection::Central,
    });
    clock.advance(1_000);
    assert_eq!(
        discover(&mut engine, clock.now()),
        0,
        "existing link suppresses reconnect"
    );

    // Link down: a fresh attempt is allowed.
    engine.handle(LinkEvent::LinkDown {
        link: 7,
        direction: LinkDirection::Central,
    });
    assert_eq!(
        discover(&mut engine, clock.now()),
        1,
        "fresh attempt after link down"
    );

    // Safety timeout: an attempt that never resolved gets retried.
    clock.advance(31_000);
    assert_eq!(
        discover(&mut engine, clock.now()),
        1,
        "retry allowed after the safety timeout"
    );

    // Radio off clears all pending attempts; radio on allows a new one.
    engine.handle(LinkEvent::RadioStateChanged(RadioState::PoweredOff));
    engine.handle(LinkEvent::RadioStateChanged(RadioState::PoweredOn));
    assert_eq!(
        discover(&mut engine, clock.now()),
        1,
        "radio cycle clears pending attempts"
    );
}

/// Non-connectable advertisers and weak-RSSI peripherals are never connected.
#[test]
fn discovery_filters_non_connectable_and_weak() {
    let clock = Clock::new(1_000_000);
    let identity = CypherIdentity::generate();
    let mut engine = MeshEngine::with_clock(identity, "alice".to_string(), clock.boxed());
    engine.handle(LinkEvent::RadioStateChanged(RadioState::PoweredOn));

    let out = engine.handle(LinkEvent::Discovered {
        peripheral: 9,
        rssi: -50,
        connectable: false,
    });
    assert!(
        out.commands
            .iter()
            .all(|c| !matches!(c, LinkCommand::Connect { peripheral: 9 })),
        "non-connectable advertiser ignored"
    );

    let out = engine.handle(LinkEvent::Discovered {
        peripheral: 10,
        rssi: -120,
        connectable: true,
    });
    assert!(
        out.commands
            .iter()
            .all(|c| !matches!(c, LinkCommand::Connect { peripheral: 10 })),
        "weak-RSSI peripheral ignored"
    );
}

// ---------------------------------------------------------------------------
// v0.3 feature surface: receipts, ACKs, files, voice, nickname, blocking
// ---------------------------------------------------------------------------

fn is_read_receipt(e: &EngineEvent) -> bool {
    matches!(
        e,
        EngineEvent::NoisePayloadReceived {
            payload_type: 0x02,
            ..
        }
    )
}

fn is_delivery_ack(e: &EngineEvent) -> bool {
    matches!(
        e,
        EngineEvent::NoisePayloadReceived {
            payload_type: 0x03,
            ..
        }
    )
}

fn is_file_received(e: &EngineEvent) -> bool {
    matches!(e, EngineEvent::FileTransferReceived { .. })
}

fn is_voice_frame(e: &EngineEvent) -> bool {
    matches!(e, EngineEvent::PublicVoiceFrameReceived { .. })
}

#[test]
fn read_receipt_and_delivery_ack_round_trip() {
    let mut world = World::new(6_000_000);
    world.bring_up();
    world.settle(4);

    let bob_id = world.bob.engine.local_peer_id();
    let out = world
        .alice
        .engine
        .send_private_message(&bob_id, "ping", "dm-r1");
    world.alice_sends(out);
    world.settle(8);

    assert!(count(&world.bob, is_noise_payload) >= 1);

    // Bob acks delivery and then marks the message read.
    let out = world
        .bob
        .engine
        .send_delivery_ack(&world.alice.engine.local_peer_id(), "dm-r1");
    world.bob_sends(out);
    let out = world
        .bob
        .engine
        .send_read_receipt(&world.alice.engine.local_peer_id(), "dm-r1");
    world.bob_sends(out);

    assert!(
        count(&world.alice, is_delivery_ack) >= 1,
        "alice should receive the delivery ACK; events: {:?}",
        world.alice.events
    );
    assert!(
        count(&world.alice, is_read_receipt) >= 1,
        "alice should receive the read receipt"
    );

    // The receipt body carries the original message id.
    let receipt = world.alice.events.iter().find_map(|e| match e {
        EngineEvent::NoisePayloadReceived {
            payload_type: 0x02,
            payload,
            ..
        } => Some(payload.clone()),
        _ => None,
    });
    assert_eq!(
        receipt,
        Some(b"dm-r1".to_vec()),
        "receipt payload = originalMessageID.utf8"
    );
}

#[test]
fn private_file_transfer_delivers_file_event() {
    let mut world = World::new(7_000_000);
    world.bring_up();
    world.settle(4);

    let bob_id = world.bob.engine.local_peer_id();
    // Establish the session first with a DM (the private-media gate
    // requires a secure session).
    let out = world
        .alice
        .engine
        .send_private_message(&bob_id, "hello", "dm-f1");
    world.alice_sends(out);
    world.settle(8);

    let png: Vec<u8> = vec![0x89, b'P', b'N', b'G', 1, 2, 3, 4];
    let out = world.alice.engine.send_file_private(
        &bob_id,
        "photo.png",
        "image/png",
        &png,
        "transfer-f1",
    );
    world.alice_sends(out);
    world.settle(8);

    let files: Vec<_> = world
        .bob
        .events
        .iter()
        .filter(|e| is_file_received(e))
        .collect();
    assert_eq!(files.len(), 1, "bob should receive exactly one file event");
    match files[0] {
        EngineEvent::FileTransferReceived {
            sender,
            file,
            is_private,
        } => {
            assert!(is_private, "typed 0x20 files must be marked private");
            assert_eq!(*sender, world.alice.engine.local_peer_id());
            assert_eq!(file.file_name, "photo.png");
            assert_eq!(file.mime_type, "image/png");
            assert_eq!(file.content, png);
        }
        _ => unreachable!(),
    }
}

#[test]
fn public_voice_burst_broadcasts_and_relays() {
    let mut world = World::new(8_000_000);
    world.bring_up();
    world.settle(4);

    let burst = cypher_core::protocol::voice::VoiceBurstPacket::start([9u8; 8], 0x01);
    let out = world.alice.engine.send_voice_burst(burst, None);
    world.alice_sends(out);
    world.settle(4);

    assert!(
        count(&world.bob, is_voice_frame) >= 1,
        "bob should receive the public voice burst; events: {:?}",
        world.bob.events
    );
}

#[test]
fn blocked_peer_cannot_receive_private_media() {
    let mut world = World::new(9_000_000);
    world.bring_up();
    world.settle(4);

    let bob_id = world.bob.engine.local_peer_id();
    let out = world
        .alice
        .engine
        .send_private_message(&bob_id, "hello", "dm-b1");
    world.alice_sends(out);
    world.settle(8);

    // Bob blocks Alice; her private file send must fail immediately.
    world
        .bob
        .engine
        .block_peer(&world.alice.engine.local_peer_id());
    let out = world.bob.engine.send_file_private(
        &world.alice.engine.local_peer_id(),
        "x.bin",
        "application/octet-stream",
        &[1, 2, 3],
        "t-b1",
    );
    let failed = out.events.iter().any(|e| {
        matches!(
            e,
            EngineEvent::MessageDeliveryStatusUpdated { failed: true, .. }
        )
    });
    assert!(failed, "send to a blocked peer must fail");
    assert!(world
        .bob
        .engine
        .is_blocked(&world.alice.engine.local_peer_id()));

    // Unblock restores the path.
    world
        .bob
        .engine
        .unblock_peer(&world.alice.engine.local_peer_id());
    assert!(!world
        .bob
        .engine
        .is_blocked(&world.alice.engine.local_peer_id()));
}

#[test]
fn nickname_change_forces_announce() {
    let mut world = World::new(10_000_000);
    world.bring_up();
    world.settle(4);

    let out = world.alice.engine.set_nickname("alice2".to_string());
    assert!(
        !out.commands.is_empty(),
        "set_nickname must broadcast an announce immediately"
    );
    world.alice_sends(out);
    world.settle(4);

    // Bob's registry reflects the new nickname.
    let alice_id = world.alice.engine.local_peer_id();
    let nickname = world
        .bob
        .engine
        .peer_info(&alice_id)
        .map(|p| p.nickname.clone());
    assert_eq!(nickname.as_deref(), Some("alice2"));
}

#[test]
fn stop_services_broadcasts_leave() {
    let clock = Clock::new(11_000_000);
    let identity = CypherIdentity::generate();
    let mut engine = MeshEngine::with_clock(identity, "alice".to_string(), clock.boxed());
    engine.handle(LinkEvent::RadioStateChanged(RadioState::PoweredOn));

    let mut out = EngineOutput::default();
    engine.stop_services(&mut out);
    assert!(
        out.commands
            .iter()
            .any(|c| matches!(c, LinkCommand::StopScan | LinkCommand::StopAdvertising)),
        "stop_services stops the radios"
    );
    // The leave packet is written to any live link; with no links the
    // send is a no-op, so at minimum the leave must not panic and the
    // radios must stop. With a live link the write is observable:
    let mut world = World::new(12_000_000);
    world.bring_up();
    world.settle(4);
    let mut out = EngineOutput::default();
    world.alice.engine.stop_services(&mut out);
    let writes = out
        .commands
        .iter()
        .filter(|c| matches!(c, LinkCommand::Write { .. }))
        .count();
    assert!(writes >= 1, "a live link receives the LEAVE write");
}
