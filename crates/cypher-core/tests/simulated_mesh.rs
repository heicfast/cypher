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
            let out = node.engine.handle(LinkEvent::RadioStateChanged(RadioState::PoweredOn));
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
                to_alice.extend(
                    out.commands
                        .into_iter()
                        .filter_map(|c| match c {
                            LinkCommand::Write { bytes, .. } => Some(bytes),
                            _ => None,
                        }),
                );
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
                back.extend(
                    out.commands
                        .into_iter()
                        .filter_map(|c| match c {
                            LinkCommand::Write { bytes, .. } => Some(bytes),
                            _ => None,
                        }),
                );
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
                to_bob.extend(
                    out.commands
                        .into_iter()
                        .filter_map(|c| match c {
                            LinkCommand::Write { bytes, .. } => Some(bytes),
                            _ => None,
                        }),
                );
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
                back.extend(
                    out.commands
                        .into_iter()
                        .filter_map(|c| match c {
                            LinkCommand::Write { bytes, .. } => Some(bytes),
                            _ => None,
                        }),
                );
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
    let out = world
        .alice
        .engine
        .send_private_message(&world.bob.engine.local_peer_id(), "the camp moved north", "dm-1");
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
    let out = world
        .bob
        .engine
        .send_private_message(&world.alice.engine.local_peer_id(), "copy that", "dm-2");
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
    assert_eq!(count(&world.bob, is_public_message), 1, "delivered exactly once");

    // Bob relays it back (echo): Alice must NOT re-deliver her own broadcast.
    world.settle(4);
    assert_eq!(
        count(&world.alice, is_public_message),
        0,
        "self-echo of own broadcast must not be delivered"
    );

    // A duplicate injection of the same wire bytes is suppressed.
    world.deliver_alice_to_bob(&commands);
    assert_eq!(count(&world.bob, is_public_message), 1, "duplicates suppressed");

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
    assert!(rtts[0] < 500, "rtt within the simulated window, got {}", rtts[0]);
}
