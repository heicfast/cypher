//! The mesh engine — the sans-I/O core owning all protocol state.
//!
//! Mirrors bitchat's engine-queue design: everything below runs on the
//! engine's single logical thread. The host feeds [`LinkEvent`]s and clock
//! ticks; the engine returns [`LinkCommand`]s (radio writes/scan/connect),
//! [`EngineEvent`]s (UI deliveries), and the next wake deadline. Timed
//! actions (relay jitter, announce cadence, maintenance) are scheduled
//! internally and executed on `tick` — no platform timers needed, fully
//! deterministic under a manual clock in tests.

use std::collections::HashMap;

use crate::consts;
use crate::features::courier::{CourierStore, CourierTier};
use crate::features::pending_queues::PendingQueues;
use crate::identity::CypherIdentity;
use crate::mesh::announce::{self, AnnounceThrottle};
use crate::mesh::dedup::MessageDeduplicator;
use crate::mesh::fanout::{select_broadcast_links, select_directed_links, FanoutLink};
use crate::mesh::fragmentation::{plan_fragments, FragmentAssemblyBuffer};
use crate::mesh::link::*;
use crate::mesh::peer_registry::{PeerInfo, PeerRegistry};
use crate::mesh::relay;
use crate::mesh::stream::StreamAssembler;
use crate::mesh::topology::MeshTopologyTracker;
use crate::noise::session_manager::{ManagerEvent, SessionManager};
use crate::noise::NoiseError;
use crate::packet::BitchatPacket;
use crate::protocol::capabilities::PeerCapabilities;
use crate::protocol::courier::CourierEnvelope;
use crate::protocol::private::{MeshPingPayload, PrivateMessagePacket};
use crate::types::{MessageType, NoisePayloadType};
use crate::util::now_ms;

/// Injectable deposit-trust policy: (peer, is_favorite, is_verified) ->
/// tier, or `None` to reject (Swift BLEService.courierDepositPolicy).
pub type CourierPolicyFn =
    Box<dyn Fn(&crate::PeerId, bool, bool) -> Option<CourierTier> + Send + Sync>;

/// UI-facing events (the engine's `TransportEvent` surface).
#[derive(Clone, Debug, serde::Serialize)]
pub enum EngineEvent {
    /// A public (broadcast) chat message was received and verified.
    PublicMessageReceived {
        sender: crate::PeerId,
        nickname: String,
        content: String,
        timestamp_ms: u64,
        message_id: String,
        is_relayed: bool,
    },
    /// A Noise-decrypted typed payload arrived (private messages, receipts,
    /// group payloads, voice, files, verification…).
    NoisePayloadReceived {
        peer: crate::PeerId,
        payload_type: u8,
        payload: Vec<u8>,
    },
    /// A group message envelope (opaque broadcast) arrived. The payload is
    /// the raw packet bytes — the UI decrypts with its group keys (parity:
    /// bitchat hands the opaque envelope to the coordinator).
    GroupMessageReceived {
        sender: crate::PeerId,
        timestamp_ms: u64,
        payload: Vec<u8>,
    },
    /// Courier envelope accepted for carriage or opened (addressed to us).
    CourierEnvelopeOpened {
        message: PrivateMessagePacket,
    },
    PeerConnected(crate::PeerId),
    PeerDisconnected(crate::PeerId),
    PeerListUpdated(Vec<crate::PeerId>),
    /// Delivery status change for an outbound message.
    MessageDeliveryStatusUpdated {
        message_id: String,
        sending: bool,
        failed: bool,
    },
    /// A mesh ping completed.
    MeshPingResult {
        peer: crate::PeerId,
        rtt_ms: u64,
        hops: Option<u8>,
    },
    BluetoothStateChanged(RadioState),
    /// A file transfer packet was received (broadcast or legacy directed).
    FileTransferReceived {
        sender: crate::PeerId,
        file: crate::protocol::file::BitchatFilePacket,
        /// True when the file arrived over a private Noise payload
        /// (0x20/0x09) instead of a public broadcast.
        is_private: bool,
    },
    /// A board payload arrived (already signature-verified).
    BoardPayloadReceived(crate::protocol::board::BoardWire),
    /// A voice frame arrived.
    PublicVoiceFrameReceived {
        peer: crate::PeerId,
        burst: crate::protocol::voice::VoiceBurstPacket,
    },
    /// Session established with a peer (mutually authenticated).
    SessionEstablished {
        peer: crate::PeerId,
    },
    /// A quarantined session was rolled back after a failed replacement
    /// handshake. `pending_convergence`: outbound drains must wait for the
    /// manager-owned convergence retry.
    SessionRestored {
        peer: crate::PeerId,
        pending_convergence: bool,
    },
    /// A session handshake failed (timeout or authentication failure).
    SessionFailed {
        peer: crate::PeerId,
    },
}

/// Everything the engine produces in one turn.
#[derive(Default, Debug)]
pub struct EngineOutput {
    pub commands: Vec<LinkCommand>,
    pub events: Vec<EngineEvent>,
    /// Earliest time the host must call `tick` (ms since epoch).
    pub next_wake_ms: Option<u64>,
}

/// A link's binding state.
#[derive(Clone, Debug)]
struct LinkBinding {
    direction: LinkDirection,
    bound_peer: Option<crate::PeerId>,
    noise_authenticated: bool,
    assembler: StreamAssembler,
}

/// Scheduled relay of a decoded packet.
struct ScheduledRelay {
    packet: BitchatPacket,
    due_ms: u64,
    /// Ingress link to exclude (split horizon).
    ingress_link: Option<LinkId>,
    message_id: String,
}

/// Pending mesh ping probes.
struct PingProbe {
    #[allow(dead_code)]
    nonce: [u8; 8],
    sent_ms: u64,
}

pub struct MeshEngine {
    identity: CypherIdentity,
    nickname: String,
    capabilities: PeerCapabilities,

    registry: PeerRegistry,
    topology: MeshTopologyTracker,
    links: HashMap<LinkId, LinkBinding>,
    /// Full session state machine (quarantine/rollback/generations).
    session_manager: SessionManager,

    dedup: MessageDeduplicator,
    fragments: FragmentAssemblyBuffer,
    announce_throttle: AnnounceThrottle,
    scheduled_relays: Vec<ScheduledRelay>,
    ping_probes: HashMap<[u8; 8], PingProbe>,

    last_announce_ms: Option<u64>,
    last_maintenance_ms: u64,
    maintenance_cycle: u64,
    radio_state: RadioState,
    scanning: bool,
    advertising: bool,
    /// Central connection attempts in flight (peripheral -> attempt time).
    /// Duplicate `Discovered` events for an in-flight peripheral are ignored
    /// (Android delivers scan hits continuously with duplicates allowed —
    /// without this guard every advertisement triggers a new `connectGatt`).
    /// Cleared on LinkUp/LinkDown; retried after the safety timeout.
    connecting: HashMap<PeripheralId, u64>,
    /// Private messages queued while no Noise session exists; drained on
    /// session establishment (bitchat's BLENoiseSessionQueues semantics).
    pending: PendingQueues,
    /// Gossip sync: broadcast-packet stores + reconciliation scheduling.
    gossip: crate::features::gossip::GossipSyncManager,
    /// Envelopes we carry for third parties.
    courier: CourierStore,
    /// Deposit trust policy (Swift BLEService.courierDepositPolicy is an
    /// injectable closure; default: favorite from registry, else verified).
    courier_policy: CourierPolicyFn,
    /// Peers we recently gossiped with (initial-sync cooldown).
    initial_synced: Vec<(crate::PeerId, u64)>,
    /// Locally blocked peers (courier deposits + private media are refused;
    /// message display filtering is the UI's job, mirroring bitchat).
    blocked: std::collections::HashSet<String>,
    /// Last time any packet arrived (feeds the announce cadence's
    /// `recentTraffic` input — bitchat: any packet within 5 s).
    last_packet_ms: Option<u64>,

    /// Injected clock (tests use a manual one).
    now_ms: crate::noise::session_manager::Clock,
}

impl MeshEngine {
    pub fn new(identity: CypherIdentity, nickname: impl Into<String>) -> Self {
        Self::with_clock(identity, nickname, Box::new(now_ms))
    }

    pub fn with_clock(
        identity: CypherIdentity,
        nickname: impl Into<String>,
        clock: Box<dyn Fn() -> u64 + Send + Sync>,
    ) -> Self {
        let start = clock();
        // One shared Arc clock for the engine, its session manager, and
        // every session the manager creates (deterministic tests).
        let clock_arc: crate::noise::session_manager::Clock = {
            let b = clock;
            std::sync::Arc::new(b)
        };
        let session_manager = SessionManager::new(
            x25519_dalek::StaticSecret::from(identity.noise_static_secret().to_bytes()),
            identity.peer_id(),
            crate::noise::session_manager::ManagerConfig::default(),
            clock_arc.clone(),
        );
        let now_ms = clock_arc.clone();
        MeshEngine {
            identity,
            nickname: nickname.into(),
            capabilities: PeerCapabilities::local_supported(),
            registry: PeerRegistry::new(),
            topology: MeshTopologyTracker::new(),
            links: HashMap::new(),
            session_manager,
            dedup: MessageDeduplicator::default(),
            fragments: FragmentAssemblyBuffer::default(),
            announce_throttle: AnnounceThrottle::default(),
            scheduled_relays: Vec::new(),
            ping_probes: HashMap::new(),
            last_announce_ms: None,
            last_maintenance_ms: start,
            maintenance_cycle: 0,
            radio_state: RadioState::Unknown,
            scanning: false,
            advertising: false,
            connecting: HashMap::new(),
            pending: PendingQueues::new(),
            gossip: crate::features::gossip::GossipSyncManager::new(),
            courier: CourierStore::new(),
            courier_policy: Box::new(|_peer, is_favorite, is_verified| {
                if is_favorite {
                    Some(CourierTier::Favorite)
                } else if is_verified {
                    Some(CourierTier::Verified)
                } else {
                    None
                }
            }),
            initial_synced: Vec::new(),
            blocked: std::collections::HashSet::new(),
            last_packet_ms: None,
            now_ms,
        }
    }

    // ---- public accessors ---------------------------------------------------

    pub fn local_peer_id(&self) -> crate::PeerId {
        self.identity.peer_id()
    }

    pub fn nickname(&self) -> &str {
        &self.nickname
    }

    /// Set the nickname and force-broadcast an announce (parity: bitchat's
    /// `setNickname` sends an announce immediately so peers see the change).
    pub fn set_nickname(&mut self, nickname: String) -> EngineOutput {
        self.nickname = nickname;
        self.announce_throttle.reset();
        let mut out = EngineOutput::default();
        let now = (self.now_ms)();
        self.send_announce(true, &mut out, now);
        out.next_wake_ms = self.next_wake(now);
        out
    }

    /// Mark a peer favorite (drives courier tiers + UI).
    pub fn set_peer_favorite(&mut self, peer: &crate::PeerId, favorite: bool) {
        if let Some(info) = self.registry.get_mut(peer) {
            info.is_favorite = favorite;
        }
    }

    /// Mark a peer verified (TOFU confirmation beyond the announce).
    pub fn set_peer_verified(&mut self, peer: &crate::PeerId, verified: bool) {
        if let Some(info) = self.registry.get_mut(peer) {
            info.is_verified = verified;
        }
    }

    /// Block a peer (courier deposits + private media refused).
    pub fn block_peer(&mut self, peer: &crate::PeerId) {
        self.blocked.insert(peer.as_str().to_string());
    }

    pub fn unblock_peer(&mut self, peer: &crate::PeerId) {
        self.blocked.remove(peer.as_str());
    }

    pub fn is_blocked(&self, peer: &crate::PeerId) -> bool {
        self.blocked.contains(peer.as_str())
    }

    pub fn blocked_peers(&self) -> Vec<crate::PeerId> {
        self.blocked
            .iter()
            .map(|s| crate::PeerId::new(s.clone()))
            .collect()
    }

    pub fn connected_peers(&self) -> Vec<crate::PeerId> {
        self.registry.connected_peers()
    }

    pub fn peer_count(&self) -> usize {
        self.registry.len()
    }

    pub fn peer_info(&self, peer: &crate::PeerId) -> Option<&PeerInfo> {
        self.registry.get(peer)
    }

    pub fn has_established_session(&self, peer: &crate::PeerId) -> bool {
        self.session_manager
            .get_session(peer)
            .map(|s| s.is_established())
            .unwrap_or(false)
    }

    pub fn can_deliver_securely(&self, peer: &crate::PeerId) -> bool {
        self.has_established_session(peer)
    }

    /// Snapshot of every known peer (for the UI).
    pub fn peer_snapshot(&self) -> Vec<crate::mesh::peer_registry::PeerInfo> {
        self.registry.all().cloned().collect()
    }

    // ---- inbound event pump -------------------------------------------------

    /// Process a radio event; returns the resulting commands and UI events.
    pub fn handle(&mut self, event: LinkEvent) -> EngineOutput {
        let mut out = EngineOutput::default();
        let now = (self.now_ms)();
        match event {
            LinkEvent::RadioStateChanged(state) => {
                self.radio_state = state;
                if state != RadioState::PoweredOn {
                    // All central attempts die with the radio.
                    self.connecting.clear();
                }
                out.events.push(EngineEvent::BluetoothStateChanged(state));
                if state == RadioState::PoweredOn {
                    self.start_services(&mut out, now);
                }
            }
            LinkEvent::BytesReceived {
                link,
                direction,
                bytes,
            } => {
                self.ingest_bytes(link, direction, &bytes, &mut out, now);
            }
            LinkEvent::LinkUp { link, direction } => {
                if direction == LinkDirection::Central {
                    // The host reuses the peripheral id as the central link id.
                    self.connecting.remove(&(link as PeripheralId));
                }
                self.links.entry(link).or_insert_with(|| LinkBinding {
                    direction,
                    bound_peer: None,
                    noise_authenticated: false,
                    assembler: StreamAssembler::new(),
                });
                // Post-subscribe announce: 0.05 s delay (immediate here).
                self.send_announce(true, &mut out, now);
            }
            LinkEvent::LinkDown { link, direction } => {
                if direction == LinkDirection::Central {
                    self.connecting.remove(&(link as PeripheralId));
                }
                self.link_down(link, &mut out, now);
            }
            LinkEvent::CentralSubscribed { link } => {
                self.links.entry(link).or_insert_with(|| LinkBinding {
                    direction: LinkDirection::Peripheral,
                    bound_peer: None,
                    noise_authenticated: false,
                    assembler: StreamAssembler::new(),
                });
                self.send_announce(true, &mut out, now);
            }
            LinkEvent::Discovered {
                peripheral,
                rssi,
                connectable,
            } => {
                self.handle_discovery(peripheral, rssi, connectable, &mut out, now);
            }
            LinkEvent::Connected { peripheral: _ } => {}
            LinkEvent::ConnectionFailed { peripheral: _ } => {}
            LinkEvent::Disconnected { peripheral: _ } => {}
            LinkEvent::LinkWritable { link: _ } => {}
            LinkEvent::LinkWriteLimit { link: _, limit: _ } => {}
        }
        out.next_wake_ms = self.next_wake(now);
        out
    }

    /// Clock tick: run due timers (relays, maintenance, announce cadence).
    pub fn tick(&mut self) -> EngineOutput {
        let mut out = EngineOutput::default();
        let now = (self.now_ms)();

        // Due relay trains.
        let due: Vec<usize> = self
            .scheduled_relays
            .iter()
            .enumerate()
            .filter(|(_, r)| r.due_ms <= now)
            .map(|(i, _)| i)
            .collect();
        for index in due.into_iter().rev() {
            let relay = self.scheduled_relays.swap_remove(index);
            self.dispatch_relay(relay, &mut out, now);
        }

        // Maintenance every 5 s.
        if now.saturating_sub(self.last_maintenance_ms)
            >= (consts::BLE_MAINTENANCE_INTERVAL_S * 1000.0) as u64
        {
            self.last_maintenance_ms = now;
            self.maintenance_cycle += 1;
            self.maintenance(&mut out, now);
            // Rekey poll (Swift: 60 s `rekeyCheckInterval` over the same
            // maintenance pump — establishments needing renegotiation get a
            // fresh XX handshake).
            for (peer, _) in self.session_manager.sessions_needing_rekey() {
                if let Ok(initiation) = self.session_manager.initiate_rekey(&peer) {
                    if let Some(m1) = self
                        .session_manager
                        .claim_handshake_initiation(&initiation, &peer)
                    {
                        let packet = BitchatPacket::new(
                            MessageType::NoiseHandshake.as_u8(),
                            self.local_peer_id().routing_bytes(),
                            Some(peer.routing_bytes()),
                            now,
                            m1,
                            consts::MESSAGE_TTL,
                        );
                        self.send_packet(packet, &mut out, now);
                    }
                }
            }
        }

        // Session-manager deadlines: initiator/responder bounds, rollback
        // restores, suppressed-initiation and recovery redispatches.
        self.session_manager.handle_deadlines(now);
        let any_link = self.links.keys().next().copied().unwrap_or(0);
        self.drain_manager_events(any_link, &mut out, now);

        out.next_wake_ms = self.next_wake(now);
        out
    }

    // ---- outbound API -------------------------------------------------------

    /// Send a public chat message (signed, broadcast).
    pub fn send_public_message(&mut self, content: &str, mentions: &[String]) -> EngineOutput {
        let mut out = EngineOutput::default();
        let now = (self.now_ms)();
        let mut packet = BitchatPacket::new(
            MessageType::Message.as_u8(),
            self.local_peer_id().routing_bytes(),
            None,
            now,
            content.as_bytes().to_vec(),
            consts::MESSAGE_TTL,
        );
        let _ = mentions;
        let preimage = packet.to_binary_data_for_signing().expect("encodable");
        packet.signature = Some(self.identity.sign(&preimage));
        self.send_packet(packet, &mut out, now);
        out.next_wake_ms = self.next_wake(now);
        out
    }

    /// Send a private message over the established Noise session.
    pub fn send_private_message(
        &mut self,
        peer: &crate::PeerId,
        content: &str,
        message_id: &str,
    ) -> EngineOutput {
        let mut out = EngineOutput::default();
        let now = (self.now_ms)();
        let session_ok = self.has_established_session(peer);

        if !session_ok {
            // Queue the message and start a handshake; the queue drains on
            // session establishment (mirrors BLENoiseSessionQueues).
            self.pending.append_private_message(
                peer,
                crate::features::pending_queues::PendingPrivateMessage {
                    content: content.to_string(),
                    message_id: message_id.to_string(),
                },
            );
            out.events.push(EngineEvent::MessageDeliveryStatusUpdated {
                message_id: message_id.to_string(),
                sending: true,
                failed: false,
            });
            self.initiate_handshake(peer, &mut out, now);
            out.next_wake_ms = self.next_wake(now);
            return out;
        }

        let Some(pm) = PrivateMessagePacket {
            message_id: message_id.to_string(),
            content: content.to_string(),
        }
        .encode() else {
            out.events.push(EngineEvent::MessageDeliveryStatusUpdated {
                message_id: message_id.to_string(),
                sending: false,
                failed: true,
            });
            return out;
        };
        let mut payload = vec![NoisePayloadType::PrivateMessage.as_u8()];
        payload.extend_from_slice(&pm);

        match self.session_manager.encrypt(&payload, peer) {
            Ok(ciphertext) => {
                let packet = BitchatPacket::new(
                    MessageType::NoiseEncrypted.as_u8(),
                    self.local_peer_id().routing_bytes(),
                    Some(peer.routing_bytes()),
                    now,
                    ciphertext,
                    consts::MESSAGE_TTL,
                );
                out.events.push(EngineEvent::MessageDeliveryStatusUpdated {
                    message_id: message_id.to_string(),
                    sending: true,
                    failed: false,
                });
                self.send_packet(packet, &mut out, now);
            }
            Err(e) => {
                out.events.push(EngineEvent::MessageDeliveryStatusUpdated {
                    message_id: message_id.to_string(),
                    sending: false,
                    failed: true,
                });
                if matches!(e, NoiseError::SessionExpired | NoiseError::SessionExhausted) {
                    self.session_manager.remove_session(peer);
                    self.initiate_handshake(peer, &mut out, now);
                }
            }
        }
        out.next_wake_ms = self.next_wake(now);
        out
    }

    /// Send an arbitrary typed Noise payload (`0x01` private-message
    /// subtypes: receipts, ACKs, group payloads, voice, files, peer state).
    /// Queues until a session exists, then encrypts and sends — parity with
    /// Swift `sendNoisePayload` + `BLENoiseSessionQueues`.
    pub fn send_typed_noise_payload(
        &mut self,
        peer: &crate::PeerId,
        payload_type: u8,
        body: &[u8],
    ) -> EngineOutput {
        let mut out = EngineOutput::default();
        let now = (self.now_ms)();
        let mut payload = Vec::with_capacity(1 + body.len());
        payload.push(payload_type);
        payload.extend_from_slice(body);

        if !self.has_established_session(peer) {
            self.pending.append_typed_payload(
                peer,
                crate::features::pending_queues::PendingTypedPayload {
                    payload: payload.clone(),
                    transfer_id: None,
                },
            );
            self.initiate_handshake(peer, &mut out, now);
            out.next_wake_ms = self.next_wake(now);
            return out;
        }

        match self.session_manager.encrypt(&payload, peer) {
            Ok(ciphertext) => {
                let packet = BitchatPacket::new(
                    MessageType::NoiseEncrypted.as_u8(),
                    self.local_peer_id().routing_bytes(),
                    Some(peer.routing_bytes()),
                    now,
                    ciphertext,
                    consts::MESSAGE_TTL,
                );
                self.send_packet(packet, &mut out, now);
            }
            Err(e) => {
                if matches!(e, NoiseError::SessionExpired | NoiseError::SessionExhausted) {
                    self.session_manager.remove_session(peer);
                    self.pending.append_typed_payload(
                        peer,
                        crate::features::pending_queues::PendingTypedPayload {
                            payload,
                            transfer_id: None,
                        },
                    );
                    self.initiate_handshake(peer, &mut out, now);
                }
            }
        }
        out.next_wake_ms = self.next_wake(now);
        out
    }

    /// Send a read receipt for a private message we have now seen
    /// (`0x02 ‖ originalMessageID.utf8`).
    pub fn send_read_receipt(
        &mut self,
        peer: &crate::PeerId,
        original_message_id: &str,
    ) -> EngineOutput {
        self.send_typed_noise_payload(
            peer,
            crate::types::NoisePayloadType::ReadReceipt.as_u8(),
            original_message_id.as_bytes(),
        )
    }

    /// Send a delivery ACK for a received private message
    /// (`0x03 ‖ messageID.utf8`).
    pub fn send_delivery_ack(&mut self, peer: &crate::PeerId, message_id: &str) -> EngineOutput {
        self.send_typed_noise_payload(
            peer,
            crate::types::NoisePayloadType::Delivered.as_u8(),
            message_id.as_bytes(),
        )
    }

    /// Broadcast a file to the mesh (signed `fileTransfer`, v2-framed by the
    /// fragmenter for large payloads).
    pub fn send_file_broadcast(
        &mut self,
        file_name: &str,
        mime_type: &str,
        content: &[u8],
    ) -> EngineOutput {
        let mut out = EngineOutput::default();
        let now = (self.now_ms)();
        let Some(encoded) =
            crate::protocol::file::BitchatFilePacket::new(file_name, mime_type, content.to_vec())
                .encode()
        else {
            out.events.push(EngineEvent::MessageDeliveryStatusUpdated {
                message_id: file_name.to_string(),
                sending: false,
                failed: true,
            });
            return out;
        };
        let mut packet = BitchatPacket::new(
            MessageType::FileTransfer.as_u8(),
            self.local_peer_id().routing_bytes(),
            None,
            now,
            encoded,
            consts::MESSAGE_TTL,
        );
        let preimage = packet.to_binary_data_for_signing().expect("file encodable");
        packet.signature = Some(self.identity.sign(&preimage));
        self.send_packet(packet, &mut out, now);
        out.next_wake_ms = self.next_wake(now);
        out
    }

    /// Send a file privately over Noise (`0x20 ‖ filePacket.encoded()`) with
    /// the private-media admission rules: blocked peers and peers without a
    /// secure path are refused.
    pub fn send_file_private(
        &mut self,
        peer: &crate::PeerId,
        file_name: &str,
        mime_type: &str,
        content: &[u8],
        transfer_id: &str,
    ) -> EngineOutput {
        let mut out = EngineOutput::default();
        if self.is_blocked(peer) || !self.can_deliver_securely(peer) {
            out.events.push(EngineEvent::MessageDeliveryStatusUpdated {
                message_id: transfer_id.to_string(),
                sending: false,
                failed: true,
            });
            return out;
        }
        let Some(encoded) =
            crate::protocol::file::BitchatFilePacket::new(file_name, mime_type, content.to_vec())
                .encode()
        else {
            out.events.push(EngineEvent::MessageDeliveryStatusUpdated {
                message_id: transfer_id.to_string(),
                sending: false,
                failed: true,
            });
            return out;
        };
        let result = self.send_typed_noise_payload(
            peer,
            crate::types::NoisePayloadType::PrivateFile.as_u8(),
            &encoded,
        );
        out.events.extend(result.events);
        out.commands.extend(result.commands);
        out.events.push(EngineEvent::MessageDeliveryStatusUpdated {
            message_id: transfer_id.to_string(),
            sending: true,
            failed: false,
        });
        out.next_wake_ms = result.next_wake_ms;
        out
    }

    /// Send a push-to-talk voice burst. `peer == None` broadcasts a signed
    /// `voiceFrame` (0x29); `Some(peer)` sends privately (`0x08` Noise
    /// payload). Bursts without a live private session are dropped (parity:
    /// Swift `sendVoiceFrame`).
    pub fn send_voice_burst(
        &mut self,
        burst: crate::protocol::voice::VoiceBurstPacket,
        peer: Option<&crate::PeerId>,
    ) -> EngineOutput {
        let mut out = EngineOutput::default();
        let now = (self.now_ms)();
        let Some(encoded) = burst.encode() else {
            return out;
        };
        match peer {
            None => {
                let mut packet = BitchatPacket::new(
                    MessageType::VoiceFrame.as_u8(),
                    self.local_peer_id().routing_bytes(),
                    None,
                    now,
                    encoded,
                    consts::MESSAGE_TTL,
                );
                let preimage = packet
                    .to_binary_data_for_signing()
                    .expect("voice encodable");
                packet.signature = Some(self.identity.sign(&preimage));
                self.send_packet(packet, &mut out, now);
            }
            Some(p) => {
                let result = self.send_typed_noise_payload(
                    p,
                    crate::types::NoisePayloadType::VoiceFrame.as_u8(),
                    &encoded,
                );
                out.events.extend(result.events);
                out.commands.extend(result.commands);
            }
        }
        out.next_wake_ms = self.next_wake(now);
        out
    }

    /// Send a mesh ping probe.
    pub fn send_mesh_ping(&mut self, peer: &crate::PeerId) -> EngineOutput {
        let mut out = EngineOutput::default();
        let now = (self.now_ms)();
        let mut nonce = [0u8; 8];
        use rand_core::RngCore;
        rand_core::OsRng.fill_bytes(&mut nonce);
        let payload = MeshPingPayload::new(nonce, consts::MESSAGE_TTL).encode();
        self.ping_probes.insert(
            nonce,
            PingProbe {
                nonce,
                sent_ms: now,
            },
        );
        let packet = BitchatPacket::new(
            MessageType::Ping.as_u8(),
            self.local_peer_id().routing_bytes(),
            Some(peer.routing_bytes()),
            now,
            payload,
            consts::MESSAGE_TTL,
        );
        self.send_packet(packet, &mut out, now);
        out.next_wake_ms = self.next_wake(now);
        out
    }

    /// Force an announce broadcast.
    pub fn announce(&mut self) -> EngineOutput {
        let mut out = EngineOutput::default();
        let now = (self.now_ms)();
        self.send_announce(true, &mut out, now);
        out.next_wake_ms = self.next_wake(now);
        out
    }

    // ---- internal: services & lifecycle --------------------------------------

    fn start_services(&mut self, out: &mut EngineOutput, now: u64) {
        if !self.scanning {
            self.scanning = true;
            out.commands.push(LinkCommand::StartScan {
                allow_duplicates: true,
            });
        }
        if !self.advertising {
            self.advertising = true;
            out.commands.push(LinkCommand::StartAdvertising);
        }
        // Initial announce after the 0.6 s delay (next tick handles it; we
        // schedule by treating the deadline as "soon").
        if self.last_announce_ms.is_none() {
            self.last_announce_ms = Some(now); // first announce goes out on the next maintenance window
        }
    }

    pub fn stop_services(&mut self, out: &mut EngineOutput) {
        let now = (self.now_ms)();
        // Signed LEAVE broadcast so peers drop us cleanly (parity: bitchat
        // broadcasts LEAVE synchronously on stopServices).
        if self.radio_state == RadioState::PoweredOn {
            let mut packet = BitchatPacket::new(
                MessageType::Leave.as_u8(),
                self.local_peer_id().routing_bytes(),
                None,
                now,
                Vec::new(),
                consts::MESSAGE_TTL,
            );
            let preimage = packet
                .to_binary_data_for_signing()
                .expect("leave encodable");
            packet.signature = Some(self.identity.sign(&preimage));
            self.send_packet(packet, out, now);
        }
        if self.scanning {
            self.scanning = false;
            out.commands.push(LinkCommand::StopScan);
        }
        if self.advertising {
            self.advertising = false;
            out.commands.push(LinkCommand::StopAdvertising);
        }
    }

    fn handle_discovery(
        &mut self,
        peripheral: PeripheralId,
        rssi: i32,
        connectable: bool,
        out: &mut EngineOutput,
        now: u64,
    ) {
        if !connectable {
            return;
        }
        // Adaptive RSSI thresholds (simplified port of the dynamic policy).
        let connected = self.registry.degree();
        let threshold = if connected == 0 {
            consts::BLE_RSSI_ISOLATED_BASE
        } else if connected >= consts::BLE_MAX_CENTRAL_LINKS {
            consts::BLE_RSSI_CONNECTED
        } else {
            consts::BLE_RSSI_DEFAULT
        };
        if rssi <= threshold {
            return;
        }
        if self.links.len() >= consts::BLE_MAX_CENTRAL_LINKS {
            return;
        }
        // Already connected to this peripheral — the host reuses the
        // peripheral id as the central link id, so an existing link binding
        // means the connection is live.
        if self.links.contains_key(&(peripheral as LinkId)) {
            return;
        }
        // Connection-storm guard: one attempt per peripheral at a time
        // (bitchat's pending-connection tracker semantics). The safety
        // timeout covers stacks that never report failure.
        if let Some(attempt_ms) = self.connecting.get(&peripheral).copied() {
            if now.saturating_sub(attempt_ms) < consts::BLE_CONNECT_RETRY_MS {
                return;
            }
        }
        self.connecting.insert(peripheral, now);
        out.commands.push(LinkCommand::Connect { peripheral });
    }

    fn link_down(&mut self, link: LinkId, out: &mut EngineOutput, now: u64) {
        if let Some(binding) = self.links.remove(&link) {
            if let Some(peer) = &binding.bound_peer {
                self.registry.set_connected(peer, false);
                out.events.push(EngineEvent::PeerDisconnected(peer.clone()));
            }
        }
        let peers: Vec<crate::PeerId> = self.registry.connected_peers();
        out.events.push(EngineEvent::PeerListUpdated(peers));
        // Restart scanning to heal the mesh after a drop.
        if self.radio_state == RadioState::PoweredOn && !self.scanning {
            self.scanning = true;
            out.commands.push(LinkCommand::StartScan {
                allow_duplicates: true,
            });
        }
        let _ = now;
    }

    // ---- internal: ingress ---------------------------------------------------

    fn ingest_bytes(
        &mut self,
        link: LinkId,
        direction: LinkDirection,
        bytes: &[u8],
        out: &mut EngineOutput,
        now: u64,
    ) {
        let Some(binding) = self.links.get_mut(&link) else {
            return;
        };
        let (frames, _reset) = binding.assembler.ingest(bytes, now);
        let ingress_peer = binding.bound_peer.clone();
        for frame in frames {
            if let Some(packet) = BitchatPacket::from_binary(&frame.0) {
                self.handle_packet(packet, link, direction, ingress_peer.clone(), out, now);
            }
        }
    }

    fn handle_packet(
        &mut self,
        packet: BitchatPacket,
        link: LinkId,
        direction: LinkDirection,
        ingress_peer: Option<crate::PeerId>,
        out: &mut EngineOutput,
        now: u64,
    ) {
        // Timestamp skew guard: ±120 s.
        let skew = now.abs_diff(packet.timestamp);
        if skew > consts::BLE_MAX_TIMESTAMP_SKEW_MS && !(packet.is_rsr && packet.ttl == 0) {
            return;
        }

        // Traffic tracking feeds the announce cadence (bitchat: any packet
        // within the last 5 s counts as recent traffic → 10 s cadence).
        self.last_packet_ms = Some(now);

        let sender = crate::PeerId::from_routing_bytes(&packet.sender_id);

        // Raw announce binding: a direct (ttl == 7) announce binds the link.
        if packet.packet_type == MessageType::Announce.as_u8() && packet.ttl == consts::MESSAGE_TTL
        {
            if let Some(binding) = self.links.get_mut(&link) {
                let already = binding
                    .bound_peer
                    .as_ref()
                    .map(|p| *p == sender)
                    .unwrap_or(false);
                if already || binding.bound_peer.is_none() {
                    binding.bound_peer = Some(sender.clone());
                }
            }
        }

        // Dedup (fragments are exempt; self sync-replay is exempt).
        let is_self_sync_replay = packet.ttl == 0 && sender == self.local_peer_id();
        let should_dedup =
            packet.packet_type != MessageType::Fragment.as_u8() && !is_self_sync_replay;
        let message_id = packet.message_identity();
        if should_dedup && self.dedup.check_and_record(&message_id, now) {
            return; // duplicate: no delivery, no relay
        }

        // Ingress peer last-seen.
        if let Some(peer) = &ingress_peer {
            self.registry.touch(peer, now);
        }
        if packet.version >= 2 {
            self.topology
                .record_observed_version(packet.sender_id, packet.version, now / 1000);
            if let Some(peer) = &ingress_peer {
                self.topology.record_observed_version(
                    peer.routing_bytes(),
                    packet.version,
                    now / 1000,
                );
            }
        }

        // Gossip: remember broadcast traffic for reconciliation.
        self.gossip.observe(&packet);

        // Type dispatch.
        match MessageType::from_u8(packet.packet_type) {
            Some(MessageType::Announce) => self.handle_announce(packet, link, &sender, out, now),
            Some(MessageType::Message) => self.handle_public_message(packet, &sender, out, now),
            Some(MessageType::Leave) => self.handle_leave(&packet, &sender, out),
            Some(MessageType::NoiseHandshake) => {
                self.handle_handshake(packet, link, &sender, out, now)
            }
            Some(MessageType::NoiseEncrypted) => {
                self.handle_encrypted(packet, link, &sender, out, now)
            }
            Some(MessageType::Fragment) => {
                self.handle_fragment(packet, link, direction, &sender, out, now)
            }
            Some(MessageType::Ping) => self.handle_ping(packet, &sender, out, now),
            Some(MessageType::Pong) => self.handle_pong(&packet, &sender, out, now),
            Some(MessageType::GroupMessage) => {
                out.events.push(EngineEvent::GroupMessageReceived {
                    sender: sender.clone(),
                    timestamp_ms: packet.timestamp,
                    payload: packet.payload.clone(),
                });
                self.schedule_relay(packet, link, &message_id, out, now);
            }
            Some(MessageType::FileTransfer) => self.handle_file_transfer(packet, &sender, out, now),
            Some(MessageType::BoardPost) => self.handle_board_post(&packet, &sender, out, now),
            Some(MessageType::VoiceFrame) => self.handle_voice_frame(packet, &sender, out, now),
            Some(MessageType::RequestSync) => {
                // Link-local (ttl 0 enforced); serve the diff back to the
                // requester as directed RSR-flagged re-sends.
                if packet.ttl == 0 {
                    if let Some(request) =
                        crate::protocol::sync::RequestSyncPacket::decode(&packet.payload)
                    {
                        if let Some(missing) = self.gossip.serve_request(&sender, &request) {
                            for frame in missing {
                                if let Some(mut response) = BitchatPacket::from_binary(&frame) {
                                    response.recipient_id = Some(sender.routing_bytes());
                                    response.ttl = 0;
                                    response.is_rsr = true;
                                    self.send_packet(response, out, now);
                                }
                            }
                        }
                    }
                }
            }
            Some(MessageType::PrekeyBundle) => {
                // Relayed transparently; the features layer hooks carry the
                // semantic handling.
                self.schedule_relay(packet, link, &message_id, out, now);
            }
            Some(MessageType::CourierEnvelope) => {
                self.handle_courier(packet, link, ingress_peer.as_ref(), &sender, out, now);
            }
            _ => {
                // Unknown types relay with the 128 KiB cap (already enforced
                // by the decoder).
                self.schedule_relay(packet, link, &message_id, out, now);
            }
        }
    }

    // ---- internal: per-type handlers -----------------------------------------

    fn handle_announce(
        &mut self,
        packet: BitchatPacket,
        link: LinkId,
        sender: &crate::PeerId,
        out: &mut EngineOutput,
        now: u64,
    ) {
        let known_noise = self.registry.get(sender).map(|p| p.noise_public_key);
        let pinned = self.registry.get(sender).and_then(|p| p.pinned_signing_key);
        let authenticated = None; // populated by the Noise layer for session-authenticated peers
        let accepted = announce::evaluate(
            &packet,
            known_noise,
            pinned,
            authenticated,
            &self.local_peer_id(),
            now,
        );
        let Ok(accepted) = accepted else {
            return; // unverified announces are ignored entirely (registry barrier)
        };

        let was_known = self.registry.get(sender).is_some();
        let was_connected = self
            .registry
            .get(sender)
            .map(|p| p.is_connected)
            .unwrap_or(false);
        let noise_key: [u8; 32] = accepted
            .packet
            .noise_public_key
            .clone()
            .try_into()
            .expect("checked in evaluate");
        let signing_key: [u8; 32] = accepted
            .packet
            .signing_public_key
            .clone()
            .try_into()
            .expect("checked in evaluate");

        // Registry upsert (before link mutation to avoid split borrows).
        let neighbors: Vec<[u8; 8]> = accepted.packet.direct_neighbors.clone().unwrap_or_default();
        self.registry.upsert_verified(
            sender.clone(),
            accepted.packet.nickname.clone(),
            noise_key,
            signing_key,
            accepted.packet.capabilities,
            now,
        );

        // Bind the link to the verified identity (direct announces only).
        if accepted.is_direct {
            if let Some(binding) = self.links.get_mut(&link) {
                binding.bound_peer = Some(sender.clone());
            }
        }

        let is_connected = accepted.is_direct || was_connected;
        self.registry.set_connected(sender, is_connected);
        // Fresh direct peers get an initial sync pass after 5 s.
        if accepted.is_direct && !was_connected {
            self.initial_synced.push((sender.clone(), now));
        }

        // Topology.
        self.topology
            .update_from_announce(sender.routing_bytes(), &neighbors, now / 1000);

        // Announce-back (deduped through the message deduplicator).
        let back_key = format!("announce-back-{}", sender.as_str());
        if !self.dedup.check_and_record(&back_key, now) {
            self.send_announce(true, out, now);
        }

        // UI events.
        if accepted.is_direct && (!was_known || !was_connected) {
            out.events.push(EngineEvent::PeerConnected(sender.clone()));
        }
        out.events.push(EngineEvent::PeerListUpdated(
            self.registry.connected_peers(),
        ));

        // Relay the announce.
        let message_id = packet.message_identity();
        self.schedule_relay(packet, link, &message_id, out, now);
    }

    fn handle_public_message(
        &mut self,
        packet: BitchatPacket,
        sender: &crate::PeerId,
        out: &mut EngineOutput,
        now: u64,
    ) {
        // Self-echo policy: our own broadcast coming back with ttl != 0 is dropped.
        if *sender == self.local_peer_id() && packet.ttl != 0 {
            return;
        }
        // Stale broadcast reject (6 h sync window).
        if now.saturating_sub(packet.timestamp) > consts::SYNC_PUBLIC_MESSAGE_MAX_AGE_S * 1000 {
            return;
        }
        // Signature required: registry key first, persisted identity fallback.
        let Some(signature) = packet.signature else {
            return;
        };
        let Some(info) = self.registry.get(sender).cloned() else {
            return; // unknown senders must announce first
        };
        let Some(preimage) = packet.to_binary_data_for_signing() else {
            return;
        };
        if !CypherIdentity::verify(&signature, &preimage, &info.signing_public_key) {
            return;
        }
        let Ok(content) = String::from_utf8(packet.payload.clone()) else {
            return;
        };
        let message_id = crate::protocol::private::MeshMessageIdentity::stable_id(
            sender.as_str(),
            packet.timestamp,
            &content,
        );
        out.events.push(EngineEvent::PublicMessageReceived {
            sender: sender.clone(),
            nickname: info.nickname.clone(),
            content,
            timestamp_ms: packet.timestamp,
            message_id,
            is_relayed: packet.ttl != consts::MESSAGE_TTL,
        });
        let mid = packet.message_identity();
        self.schedule_relay(packet, LinkId::MAX, &mid, out, now);
    }

    fn handle_leave(
        &mut self,
        packet: &BitchatPacket,
        sender: &crate::PeerId,
        out: &mut EngineOutput,
    ) {
        // Signature must verify against the registry key.
        let Some(signature) = &packet.signature else {
            return;
        };
        let Some(info) = self.registry.get(sender).cloned() else {
            return;
        };
        let Some(preimage) = packet.to_binary_data_for_signing() else {
            return;
        };
        if !CypherIdentity::verify(signature, &preimage, &info.signing_public_key) {
            return;
        }
        self.session_manager.remove_session(sender);
        self.registry.remove(sender);
        out.events
            .push(EngineEvent::PeerDisconnected(sender.clone()));
        out.events.push(EngineEvent::PeerListUpdated(
            self.registry.connected_peers(),
        ));
    }

    fn handle_handshake(
        &mut self,
        packet: BitchatPacket,
        link: LinkId,
        sender: &crate::PeerId,
        out: &mut EngineOutput,
        now: u64,
    ) {
        // Only process when addressed to us.
        if let Some(recipient) = &packet.recipient_id {
            if crate::PeerId::from_routing_bytes(recipient) != self.local_peer_id() {
                let mid = packet.message_identity();
                self.schedule_relay(packet, link, &mid, out, now);
                return;
            }
        }

        // Route through the full manager state machine: quarantine,
        // rollback cooldown, crossed-initiation gates, generation leases.
        match self
            .session_manager
            .handle_incoming_with_result(sender, &packet.payload)
        {
            Ok(result) => {
                // The reply goes on the wire BEFORE any session-established
                // side effects — otherwise the peer receives encrypted
                // traffic before the completing handshake message and
                // clobbers its responder state with a crossed initiation.
                if let Some(resp) = result.response {
                    let reply = BitchatPacket::new(
                        MessageType::NoiseHandshake.as_u8(),
                        self.local_peer_id().routing_bytes(),
                        Some(sender.routing_bytes()),
                        now,
                        resp,
                        consts::MESSAGE_TTL,
                    );
                    self.send_packet(reply, out, now);
                }
            }
            Err(_) => {
                // The manager buffered restore/failure events; the tick
                // pump (and the drain below) surfaces them to the UI.
            }
        }
        self.drain_manager_events(link, out, now);
    }

    /// Translate buffered session-manager events into engine actions.
    fn drain_manager_events(&mut self, link: LinkId, out: &mut EngineOutput, now: u64) {
        for event in self.session_manager.take_events() {
            match event {
                ManagerEvent::SessionEstablished { peer, .. } => {
                    out.events
                        .push(EngineEvent::SessionEstablished { peer: peer.clone() });
                    self.on_session_established(&peer, link, out, now);
                }
                ManagerEvent::SessionRestored { peer, reason, .. } => {
                    // Terminal restore: the generation is immediately valid
                    // for outbound; pending convergence: outbound defers to
                    // the manager-owned retry.
                    let pending_convergence =
                        reason == crate::noise::session_manager::RestoreReason::PendingConvergence;
                    out.events.push(EngineEvent::SessionRestored {
                        peer,
                        pending_convergence,
                    });
                }
                ManagerEvent::SessionFailed { peer, .. } => {
                    out.events.push(EngineEvent::SessionFailed { peer });
                }
                ManagerEvent::HandshakeRecoveryRequired { request } => {
                    // The engine is the transport callback owner: prepare,
                    // claim, and put the recovery message 1 on the wire.
                    match self
                        .session_manager
                        .prepare_handshake_recovery(&request, || Ok(()))
                    {
                        Ok(Some(crate::noise::session_manager::RecoveryPreparation::Ordinary(
                            initiation,
                        ))) => {
                            if let Some(m1) = self
                                .session_manager
                                .claim_handshake_initiation(&initiation, &request.peer_id)
                            {
                                let packet = BitchatPacket::new(
                                    MessageType::NoiseHandshake.as_u8(),
                                    self.local_peer_id().routing_bytes(),
                                    Some(request.peer_id.routing_bytes()),
                                    now,
                                    m1,
                                    consts::MESSAGE_TTL,
                                );
                                self.send_packet(packet, out, now);
                            }
                        }
                        Ok(Some(
                            crate::noise::session_manager::RecoveryPreparation::Transferred,
                        )) => {}
                        Ok(None) => {}
                        Err(_) => {}
                    }
                }
            }
        }
    }

    fn on_session_established(
        &mut self,
        peer: &crate::PeerId,
        link: LinkId,
        out: &mut EngineOutput,
        now: u64,
    ) {
        // Mark the ingress link Noise-authenticated when it is bound to the peer.
        if let Some(binding) = self.links.get_mut(&link) {
            if binding.bound_peer.as_ref() == Some(peer) {
                binding.noise_authenticated = true;
            }
        }
        // Re-announce with force so the peer sees us connected.
        self.send_announce(true, out, now);
        // 0x21 exchange: tell the peer our capabilities + signing key (parity:
        // bitchat sends AuthenticatedPeerState on every session establishment).
        {
            let state = crate::protocol::capabilities::authenticated_peer_state(
                self.capabilities,
                &self.identity.signing_public(),
            );
            let sent = self.send_typed_noise_payload(
                peer,
                crate::types::NoisePayloadType::AuthenticatedPeerState.as_u8(),
                &state,
            );
            out.events.extend(sent.events);
            out.commands.extend(sent.commands);
        }
        // Drain queued private messages for this peer (one-shot FIFO;
        // failures would re-queue at the front to keep order).
        let queued = self.pending.take_private_messages(peer);
        let mut failed = Vec::new();
        for message in queued {
            let drained = self.send_private_message(peer, &message.content, &message.message_id);
            // A drain-time failure (session died mid-drain) goes back to the
            // FRONT so it precedes anything queued later (Swift semantics).
            let failed_now = drained.events.iter().any(|e| {
                matches!(
                    e,
                    EngineEvent::MessageDeliveryStatusUpdated { failed: true, .. }
                )
            });
            if failed_now {
                failed.push(message);
            }
            out.events.extend(drained.events);
            out.commands.extend(drained.commands);
        }
        if !failed.is_empty() {
            self.pending.prepend_private_messages(peer, failed);
        }
        // Drain queued typed payloads (receipts, files, voice, peer state).
        let typed = self.pending.take_typed_payloads(peer);
        for queued_payload in typed {
            let (payload_type, body) = queued_payload
                .payload
                .split_first()
                .map(|(t, rest)| (*t, rest.to_vec()))
                .unwrap_or((0, Vec::new()));
            let drained = self.send_typed_noise_payload(peer, payload_type, &body);
            out.events.extend(drained.events);
            out.commands.extend(drained.commands);
        }
        // Courier: spray carried copies to trusted (courier-tier) peers we
        // now share a session with (parity: transferSprayCopies on encounter).
        if self.courier_tier_for(peer).is_some() {
            let noise_key = self
                .registry
                .get(peer)
                .map(|p| p.noise_public_key)
                .unwrap_or([0u8; 32]);
            let envelopes = self.courier.transfer_spray_copies(&noise_key);
            for stored in envelopes {
                let wire = CourierEnvelope::new(
                    stored.recipient_tag.clone(),
                    stored.expiry,
                    stored.ciphertext.clone(),
                    1,
                    stored.prekey_id,
                );
                let Some(bytes) = wire.encode() else { continue };
                let mut packet = BitchatPacket::new(
                    MessageType::CourierEnvelope.as_u8(),
                    self.local_peer_id().routing_bytes(),
                    Some(peer.routing_bytes()),
                    now,
                    bytes,
                    consts::MESSAGE_TTL,
                );
                let preimage = packet
                    .to_binary_data_for_signing()
                    .expect("envelope encodable");
                packet.signature = Some(self.identity.sign(&preimage));
                self.send_packet(packet, out, now);
            }
        }
    }

    /// The courier deposit tier a peer qualifies for under the active policy
    /// (favorite → Favorite, verified → Verified, else None).
    fn courier_tier_for(
        &self,
        peer: &crate::PeerId,
    ) -> Option<crate::features::courier::CourierTier> {
        let info = self.registry.get(peer)?;
        (self.courier_policy)(peer, info.is_favorite, info.is_verified)
    }

    fn handle_encrypted(
        &mut self,
        packet: BitchatPacket,
        link: LinkId,
        sender: &crate::PeerId,
        out: &mut EngineOutput,
        now: u64,
    ) {
        // Only process when addressed to us; others relay.
        if let Some(recipient) = &packet.recipient_id {
            if crate::PeerId::from_routing_bytes(recipient) != self.local_peer_id() {
                let mid = packet.message_identity();
                self.schedule_relay(packet, link, &mid, out, now);
                return;
            }
        }
        match self.session_manager.decrypt(&packet.payload, sender) {
            Ok(plaintext) => {
                let Some(&payload_type) = plaintext.first() else {
                    return;
                };
                let payload = plaintext[1..].to_vec();
                match NoisePayloadType::from_u8(payload_type) {
                    Some(NoisePayloadType::PrivateMessage) => {
                        if let Some(pm) = PrivateMessagePacket::decode(&payload) {
                            out.events.push(EngineEvent::NoisePayloadReceived {
                                peer: sender.clone(),
                                payload_type,
                                payload,
                            });
                            let _ = pm;
                        }
                    }
                    Some(NoisePayloadType::ReadReceipt) => {
                        out.events.push(EngineEvent::NoisePayloadReceived {
                            peer: sender.clone(),
                            payload_type,
                            payload,
                        });
                    }
                    Some(NoisePayloadType::Delivered) => {
                        out.events.push(EngineEvent::NoisePayloadReceived {
                            peer: sender.clone(),
                            payload_type,
                            payload,
                        });
                    }
                    Some(NoisePayloadType::AuthenticatedPeerState) => {
                        // Capability + signing-key exchange: pin into the
                        // registry (parity: bitchat consumes 0x21 internally).
                        if let Some((caps, signing)) =
                            crate::protocol::capabilities::parse_authenticated_peer_state(&payload)
                        {
                            if let Some(info) = self.registry.get_mut(sender) {
                                info.capabilities = Some(caps);
                                if info.signing_public_key == signing
                                    || info.pinned_signing_key == Some(signing)
                                {
                                    info.signing_public_key = signing;
                                    info.pinned_signing_key = Some(signing);
                                }
                            }
                        }
                    }
                    Some(NoisePayloadType::PrivateFile) => {
                        // Typed private media (0x20): decode + surface as a
                        // file event so the UI has one file pipeline.
                        if let Some(file) =
                            crate::protocol::file::BitchatFilePacket::decode(&payload)
                        {
                            out.events.push(EngineEvent::FileTransferReceived {
                                sender: sender.clone(),
                                file,
                                is_private: true,
                            });
                        }
                    }
                    Some(NoisePayloadType::LegacyPrivateFile) => {
                        if let Some(file) =
                            crate::protocol::file::BitchatFilePacket::decode(&payload)
                        {
                            out.events.push(EngineEvent::FileTransferReceived {
                                sender: sender.clone(),
                                file,
                                is_private: true,
                            });
                        }
                    }
                    _ => {
                        out.events.push(EngineEvent::NoisePayloadReceived {
                            peer: sender.clone(),
                            payload_type,
                            payload,
                        });
                    }
                }
            }
            Err(e) => match e {
                // Drop-only failures never clear the session (attacker
                // bytes can't evict working keys).
                NoiseError::AuthenticationFailure
                | NoiseError::ReplayDetected
                | NoiseError::InvalidCiphertext
                | NoiseError::MessageTooLarge => {}
                // Local lifecycle failures: reset and re-handshake.
                NoiseError::SessionExpired
                | NoiseError::SessionExhausted
                | NoiseError::NotEstablished
                | NoiseError::SessionNotFound
                | NoiseError::TransportGenerationNotReady => {
                    self.initiate_handshake(sender, out, now);
                }
                _ => {}
            },
        }
    }

    fn handle_fragment(
        &mut self,
        packet: BitchatPacket,
        link: LinkId,
        direction: LinkDirection,
        sender: &crate::PeerId,
        out: &mut EngineOutput,
        now: u64,
    ) {
        // Self-authored broadcast fragments are tracked, not assembled.
        if *sender == self.local_peer_id() {
            return;
        }
        match self.fragments.ingest(&packet, now) {
            Some(crate::mesh::fragmentation::AssemblyOutcome::Complete(bytes)) => {
                if let Some(original) = BitchatPacket::from_binary(&bytes) {
                    // Link-local reinjection: ttl = 0, delivered by the peer.
                    let mut reinjected = original;
                    reinjected.ttl = 0;
                    self.handle_packet(reinjected, link, direction, Some(sender.clone()), out, now);
                }
            }
            Some(crate::mesh::fragmentation::AssemblyOutcome::ForgedType) => {}
            Some(crate::mesh::fragmentation::AssemblyOutcome::Oversized) => {}
            Some(crate::mesh::fragmentation::AssemblyOutcome::Progress) => {
                // Relay fragments onward (flood).
                let mid = packet.message_identity();
                self.schedule_relay(packet, link, &mid, out, now);
            }
            None => {}
        }
    }

    fn handle_ping(
        &mut self,
        packet: BitchatPacket,
        sender: &crate::PeerId,
        out: &mut EngineOutput,
        now: u64,
    ) {
        let Some(recipient) = &packet.recipient_id else {
            return;
        };
        if crate::PeerId::from_routing_bytes(recipient) != self.local_peer_id() {
            let mid = packet.message_identity();
            self.schedule_relay(packet, LinkId::MAX, &mid, out, now);
            return;
        }
        let Some(ping) = MeshPingPayload::decode(&packet.payload) else {
            return;
        };
        // Reply pong: echoed nonce, origin TTL 7.
        let pong = MeshPingPayload::new(ping.nonce, consts::MESSAGE_TTL).encode();
        let reply = BitchatPacket::new(
            MessageType::Pong.as_u8(),
            self.local_peer_id().routing_bytes(),
            Some(sender.routing_bytes()),
            now,
            pong,
            consts::MESSAGE_TTL,
        );
        self.send_packet(reply, out, now);
    }

    fn handle_pong(
        &mut self,
        packet: &BitchatPacket,
        sender: &crate::PeerId,
        out: &mut EngineOutput,
        now: u64,
    ) {
        let Some(pong) = MeshPingPayload::decode(&packet.payload) else {
            return;
        };
        if let Some(probe) = self.ping_probes.remove(&pong.nonce) {
            let rtt = now.saturating_sub(probe.sent_ms);
            let hops = MeshPingPayload::hop_count(pong.origin_ttl, packet.ttl);
            out.events.push(EngineEvent::MeshPingResult {
                peer: sender.clone(),
                rtt_ms: rtt,
                hops,
            });
        }
    }

    fn handle_file_transfer(
        &mut self,
        packet: BitchatPacket,
        sender: &crate::PeerId,
        out: &mut EngineOutput,
        now: u64,
    ) {
        // Raw file transfers must be signed.
        let Some(signature) = &packet.signature else {
            return;
        };
        let Some(info) = self.registry.get(sender).cloned() else {
            return;
        };
        let Some(preimage) = packet.to_binary_data_for_signing() else {
            return;
        };
        if !CypherIdentity::verify(signature, &preimage, &info.signing_public_key) {
            return;
        }
        // Self-echo drop.
        if *sender == self.local_peer_id() && packet.ttl != 0 {
            return;
        }
        let addressed_to_me = packet
            .recipient_id
            .as_ref()
            .map(|r| crate::PeerId::from_routing_bytes(r) == self.local_peer_id())
            .unwrap_or(false);
        let broadcast = packet.recipient_id.is_none()
            || packet.recipient_id == Some(crate::PeerId::broadcast_marker());
        if addressed_to_me || broadcast {
            if let Some(file) = crate::protocol::file::BitchatFilePacket::decode(&packet.payload) {
                out.events.push(EngineEvent::FileTransferReceived {
                    sender: sender.clone(),
                    file,
                    is_private: false,
                });
            }
        }
        let mid = packet.message_identity();
        self.schedule_relay(packet, LinkId::MAX, &mid, out, now);
    }

    fn handle_board_post(
        &mut self,
        packet: &BitchatPacket,
        _sender: &crate::PeerId,
        out: &mut EngineOutput,
        now: u64,
    ) {
        if let Some(wire) = crate::protocol::board::BoardWire::decode(&packet.payload) {
            if wire.verify_signature() {
                out.events.push(EngineEvent::BoardPayloadReceived(wire));
            }
        }
        self.schedule_relay(
            packet.clone(),
            LinkId::MAX,
            &packet.message_identity(),
            out,
            now,
        );
    }

    fn handle_voice_frame(
        &mut self,
        packet: BitchatPacket,
        sender: &crate::PeerId,
        out: &mut EngineOutput,
        now: u64,
    ) {
        // Public voice: freshness 30 s, signature-verified.
        if now.saturating_sub(packet.timestamp) > consts::PTT_PUBLIC_FRAME_MAX_AGE_S * 1000 {
            return;
        }
        let Some(signature) = &packet.signature else {
            return;
        };
        let Some(info) = self.registry.get(sender).cloned() else {
            return;
        };
        let Some(preimage) = packet.to_binary_data_for_signing() else {
            return;
        };
        if !CypherIdentity::verify(signature, &preimage, &info.signing_public_key) {
            return;
        }
        if let Some(burst) = crate::protocol::voice::VoiceBurstPacket::decode(&packet.payload) {
            out.events.push(EngineEvent::PublicVoiceFrameReceived {
                peer: sender.clone(),
                burst,
            });
        }
        self.schedule_relay(
            packet.clone(),
            LinkId::MAX,
            &packet.message_identity(),
            out,
            now,
        );
    }

    // ---- internal: outbound & relay -------------------------------------------

    /// Send a packet on the appropriate links (fragmenting when needed).
    fn send_packet(&mut self, packet: BitchatPacket, out: &mut EngineOutput, now: u64) {
        // Fragment when the frame exceeds the minimum link limit.
        let min_limit = self
            .links
            .values()
            .map(|_| consts::BLE_DEFAULT_FRAGMENT_SIZE)
            .min()
            .unwrap_or(consts::BLE_DEFAULT_FRAGMENT_SIZE);
        let encoded_len = packet
            .to_binary_data(false)
            .map(|d| d.len())
            .unwrap_or(usize::MAX);
        if encoded_len > min_limit && packet.packet_type != MessageType::Fragment.as_u8() {
            if let Some(fragments) = plan_fragments(&packet, None, now) {
                for fragment in fragments {
                    self.send_packet(fragment, out, now);
                }
            }
            return;
        }

        let directed = packet.recipient_id.map(|r| {
            let id = crate::PeerId::from_routing_bytes(&r);
            !(id.as_str() == crate::util::hex_encode(&crate::PeerId::broadcast_marker()))
        });
        let candidates: Vec<FanoutLink> = self
            .links
            .iter()
            .map(|(link, binding)| FanoutLink {
                link: *link,
                direction: binding.direction,
                bound_peer: binding.bound_peer.clone(),
            })
            .collect();

        let targets: Vec<LinkId> = match (&packet.recipient_id, directed) {
            (Some(recipient), Some(true)) => {
                let target = crate::PeerId::from_routing_bytes(recipient);
                select_directed_links(&candidates, &target)
            }
            _ => select_broadcast_links(&packet.message_identity(), &candidates, &[]),
        };

        let pad_frame = matches!(
            MessageType::from_u8(packet.packet_type),
            Some(MessageType::NoiseEncrypted) | Some(MessageType::NoiseHandshake)
        );
        if let Some(encoded) = packet.to_binary_data(pad_frame) {
            for link in targets {
                let direction = self
                    .links
                    .get(&link)
                    .map(|b| b.direction)
                    .unwrap_or(LinkDirection::Central);
                out.commands.push(LinkCommand::Write {
                    link,
                    direction,
                    bytes: encoded.clone(),
                    priority: match MessageType::from_u8(packet.packet_type) {
                        Some(MessageType::Fragment) => WritePriority::Fragment(0),
                        Some(MessageType::FileTransfer) => WritePriority::FileTransfer,
                        Some(MessageType::NoiseEncrypted) | Some(MessageType::NoiseHandshake) => {
                            WritePriority::High
                        }
                        _ => WritePriority::Low,
                    },
                });
            }
        }
    }

    fn schedule_relay(
        &mut self,
        packet: BitchatPacket,
        ingress_link: LinkId,
        message_id: &str,
        _out: &mut EngineOutput,
        now: u64,
    ) {
        let ingress_peer = self
            .links
            .get(&ingress_link)
            .and_then(|b| b.bound_peer.clone());
        let degree = self.registry.degree();
        let decision = relay::decide(
            &packet,
            &ingress_peer.clone().unwrap_or_else(|| self.local_peer_id()),
            &self.local_peer_id(),
            degree,
        );
        if !decision.should_relay {
            return;
        }
        let mut relayed = packet;
        relayed.ttl = decision.new_ttl;
        // Reuse the originator's payload bytes (signature preimage stability).
        let jitter = decision.jitter_min_ms
            + (rand_u64() % (decision.jitter_max_ms - decision.jitter_min_ms + 1).max(1));
        self.scheduled_relays.push(ScheduledRelay {
            packet: relayed,
            due_ms: now + jitter,
            ingress_link: if ingress_link == LinkId::MAX {
                None
            } else {
                Some(ingress_link)
            },
            message_id: message_id.to_string(),
        });
    }

    fn dispatch_relay(&mut self, relay: ScheduledRelay, out: &mut EngineOutput, now: u64) {
        let candidates: Vec<FanoutLink> = self
            .links
            .iter()
            .map(|(link, binding)| FanoutLink {
                link: *link,
                direction: binding.direction,
                bound_peer: binding.bound_peer.clone(),
            })
            .collect();
        let excluded: Vec<LinkId> = relay.ingress_link.into_iter().collect();
        let is_directed = relay
            .packet
            .recipient_id
            .map(|r| {
                crate::PeerId::from_routing_bytes(&r).as_str()
                    != crate::util::hex_encode(&crate::PeerId::broadcast_marker())
            })
            .unwrap_or(false);
        let targets = if is_directed {
            relay
                .packet
                .recipient_id
                .map(|r| select_directed_links(&candidates, &crate::PeerId::from_routing_bytes(&r)))
                .unwrap_or_default()
        } else {
            select_broadcast_links(&relay.message_id, &candidates, &excluded)
        };
        let pad_frame = matches!(
            MessageType::from_u8(relay.packet.packet_type),
            Some(MessageType::NoiseEncrypted) | Some(MessageType::NoiseHandshake)
        );
        if let Some(encoded) = relay.packet.to_binary_data(pad_frame) {
            for link in targets {
                let direction = self
                    .links
                    .get(&link)
                    .map(|b| b.direction)
                    .unwrap_or(LinkDirection::Central);
                out.commands.push(LinkCommand::Write {
                    link,
                    direction,
                    bytes: encoded.clone(),
                    priority: WritePriority::Low,
                });
            }
        }
        let _ = now;
    }

    /// Start (or join) a handshake with a peer via the session manager:
    /// initiate-if-absent + one-shot claim, then put message 1 on the wire.
    fn initiate_handshake(&mut self, peer: &crate::PeerId, out: &mut EngineOutput, now: u64) {
        if let Ok(Some(initiation)) =
            self.session_manager
                .initiate_handshake_if_absent(peer, true, || Ok(()))
        {
            if let Some(m1) = self
                .session_manager
                .claim_handshake_initiation(&initiation, peer)
            {
                let packet = BitchatPacket::new(
                    MessageType::NoiseHandshake.as_u8(),
                    self.local_peer_id().routing_bytes(),
                    Some(peer.routing_bytes()),
                    now,
                    m1,
                    consts::MESSAGE_TTL,
                );
                self.send_packet(packet, out, now);
            }
        }
    }

    /// Courier ingress: open envelopes addressed to us; admit deposits
    /// from directly-connected peers (Swift `acceptCourierDeposit`).
    fn handle_courier(
        &mut self,
        packet: BitchatPacket,
        link: LinkId,
        ingress_peer: Option<&crate::PeerId>,
        sender: &crate::PeerId,
        out: &mut EngineOutput,
        now: u64,
    ) {
        let relay_mid = packet.message_identity();
        let Some(envelope) = CourierEnvelope::decode(&packet.payload) else {
            self.schedule_relay(packet, link, &relay_mid, out, now);
            return;
        };
        // Addressed to us? (our rotating day tags, day-1..day+1)
        let day_tags = self.our_day_tags(now);
        if day_tags
            .iter()
            .any(|t| t.as_slice() == envelope.recipient_tag.as_slice())
        {
            // Open the seal addressed to our Noise static key.
            let opened = if let Some(_prekey_id) = envelope.prekey_id {
                None // prekey seals open via the features prekey store
            } else {
                crate::features::courier::open_courier_payload(
                    &envelope.ciphertext,
                    self.identity.noise_static_secret(),
                )
                .ok()
                .and_then(|(payload, _sender_static)| {
                    // Plaintext = [type byte 0x01][PrivateMessagePacket TLV].
                    let pm = &payload[1..];
                    crate::protocol::private::PrivateMessagePacket::decode(pm)
                })
            };
            if let Some(pm) = opened {
                out.events
                    .push(EngineEvent::CourierEnvelopeOpened { message: pm });
            }
            return;
        }
        // Transport-level deposit admission (relayed deposits rejected:
        // a stranger can't ride a trusted neighbor's quota).
        let Some(ingress) = ingress_peer else {
            self.schedule_relay(packet, link, &relay_mid, out, now);
            return;
        };
        if ingress != sender {
            self.schedule_relay(packet, link, &relay_mid, out, now);
            return;
        }
        // Depositor must be a known peer with a Noise key.
        let Some(info) = self.registry.get(sender).cloned() else {
            return;
        };
        // Blocked senders cannot deposit (parity: Swift drops courier
        // envelopes from blocked identities).
        if self.is_blocked(sender) {
            return;
        }
        // The packet's signature must verify against the announced key.
        if let Some(signature) = &packet.signature {
            let Some(preimage) = packet.to_binary_data_for_signing() else {
                return;
            };
            if !CypherIdentity::verify(signature, &preimage, &info.signing_public_key) {
                return;
            }
        } else {
            return;
        }
        // Tier decision via the injectable policy.
        let tier = (self.courier_policy)(sender, info.is_favorite, info.is_verified);
        let Some(tier) = tier else {
            return; // neither favorite nor verified
        };
        let now_ms = now;
        self.courier
            .deposit(envelope, info.noise_public_key.to_vec(), tier, now_ms);
    }

    /// Our rotating recipient tags for day-1, day, day+1.
    fn our_day_tags(&self, now_ms: u64) -> Vec<[u8; 16]> {
        let day = CourierEnvelope::epoch_day(now_ms / 1000);
        let static_key = self.identity.noise_static_public();
        [-1i64, 0, 1]
            .iter()
            .map(|offset| {
                CourierEnvelope::recipient_tag(
                    static_key.as_slice(),
                    (day as i64 + offset).max(0) as u32,
                )
            })
            .collect()
    }

    /// Override the deposit trust policy (app-level favorites/verification).
    pub fn set_courier_policy(&mut self, policy: CourierPolicyFn) {
        self.courier_policy = policy;
    }

    /// Access the carried-envelope store (for the handover/spray tick).
    pub fn courier_store_mut(&mut self) -> &mut CourierStore {
        &mut self.courier
    }

    fn send_announce(&mut self, forced: bool, out: &mut EngineOutput, now: u64) {
        if !self.announce_throttle.may_send(forced, now) {
            return;
        }
        let neighbors: Vec<[u8; 8]> = self
            .registry
            .connected_peers()
            .iter()
            .take(consts::TOPOLOGY_MAX_NEIGHBORS_PER_ANNOUNCE)
            .map(|p| p.routing_bytes())
            .collect();
        let Some(payload) = announce::build_payload(
            &self.nickname,
            &self.identity.noise_static_public(),
            &self.identity.signing_public(),
            &neighbors,
            self.capabilities,
        ) else {
            return;
        };
        let mut packet = BitchatPacket::new(
            MessageType::Announce.as_u8(),
            self.local_peer_id().routing_bytes(),
            None,
            now,
            payload,
            consts::MESSAGE_TTL,
        );
        let preimage = packet
            .to_binary_data_for_signing()
            .expect("announce encodable");
        packet.signature = Some(self.identity.sign(&preimage));
        self.announce_throttle.record(forced, now);
        self.last_announce_ms = Some(now);
        self.send_packet(packet, out, now);
    }

    fn maintenance(&mut self, out: &mut EngineOutput, now: u64) {
        // Announce cadence.
        let elapsed = self
            .last_announce_ms
            .map(|t| (now.saturating_sub(t)) as f64 / 1000.0)
            .unwrap_or(f64::MAX);
        let recent_traffic = self
            .last_packet_ms
            .map(|t| now.saturating_sub(t) < 5_000)
            .unwrap_or(false);
        if announce::should_announce(
            self.maintenance_cycle,
            self.registry.degree(),
            recent_traffic,
            elapsed,
        ) {
            self.send_announce(false, out, now);
        }

        // Fragment expiry + dedup cleanup.
        self.fragments.remove_expired(now);
        self.dedup.cleanup(now);

        // Carried courier envelopes expire (parity: removeExpired sweep).
        self.courier.remove_expired(now);

        // Connectivity reconciliation.
        let live: Vec<crate::PeerId> = self
            .links
            .values()
            .filter_map(|b| b.bound_peer.clone())
            .collect();
        let (disconnected, _removed) = self.registry.reconcile(now, &live);
        for peer in disconnected {
            out.events.push(EngineEvent::PeerDisconnected(peer));
        }
        out.events.push(EngineEvent::PeerListUpdated(
            self.registry.connected_peers(),
        ));

        // Topology pruning.
        self.topology.prune(now / 1000);

        // Ping probe expiry (10 s).
        self.ping_probes.retain(|_, p| {
            now.saturating_sub(p.sent_ms) < (consts::MESH_PING_TIMEOUT_S * 1000.0) as u64
        });

        // Gossip rounds: build REQUEST_SYNC for due store classes — unicast
        // to EVERY connected peer with broadcast fallback (parity: Swift
        // `GossipSyncManager` periodic rounds).
        if let Some(classes) = self.gossip.sync_due() {
            if let Some(request) = self.gossip.build_request(&classes) {
                if let Some(payload) = request.encode() {
                    let targets = self.registry.connected_peers();
                    for peer in targets {
                        self.gossip.request_manager.record_request(&peer, now);
                        let mut packet = BitchatPacket::new(
                            MessageType::RequestSync.as_u8(),
                            self.local_peer_id().routing_bytes(),
                            Some(peer.routing_bytes()),
                            now,
                            payload.clone(),
                            0, // link-local
                        );
                        let preimage = packet.to_binary_data_for_signing().expect("sync encodable");
                        packet.signature = Some(self.identity.sign(&preimage));
                        self.send_packet(packet, out, now);
                    }
                }
            }
        }

        // Initial sync to freshly connected peers (1 s after connect —
        // parity: Swift `scheduleInitialSyncToPeer` delay).
        let initial_delay = consts::SYNC_INITIAL_SYNC_DELAY_S * 1000;
        // Snapshot + one-shot pass (the closure cannot borrow the engine).
        let pending: Vec<(crate::PeerId, u64)> = std::mem::take(&mut self.initial_synced);
        for (peer, at) in pending {
            let connected = self
                .registry
                .get(&peer)
                .map(|p| p.is_connected)
                .unwrap_or(false);
            if !connected {
                continue; // peer left before the sync fired
            }
            let age = now.saturating_sub(at);
            if age < initial_delay {
                self.initial_synced.push((peer, at)); // not yet due
                continue;
            }
            if age >= initial_delay + 60_000 {
                continue; // one-shot lapsed
            }
            let classes = vec![
                crate::features::gossip::StoreClass::Messages,
                crate::features::gossip::StoreClass::Fragments,
                crate::features::gossip::StoreClass::Files,
                crate::features::gossip::StoreClass::Prekeys,
                crate::features::gossip::StoreClass::Board,
                crate::features::gossip::StoreClass::GroupMessages,
            ];
            if let Some(request) = self.gossip.build_request(&classes) {
                if let Some(payload) = request.encode() {
                    self.gossip.request_manager.record_request(&peer, now);
                    let mut packet = BitchatPacket::new(
                        MessageType::RequestSync.as_u8(),
                        self.local_peer_id().routing_bytes(),
                        Some(peer.routing_bytes()),
                        now,
                        payload,
                        0,
                    );
                    let preimage = packet.to_binary_data_for_signing().expect("sync encodable");
                    packet.signature = Some(self.identity.sign(&preimage));
                    self.send_packet(packet, out, now);
                }
            }
        }

        // Every third cycle: deeper cleanup (already covered incrementally).
    }

    fn next_wake(&self, now: u64) -> Option<u64> {
        let mut candidates: Vec<u64> = Vec::new();
        if let Some(next) = self.scheduled_relays.iter().map(|r| r.due_ms).min() {
            candidates.push(next);
        }
        candidates.push(
            now + ((consts::BLE_MAINTENANCE_INTERVAL_S
                - now.saturating_sub(self.last_maintenance_ms) as f64 / 1000.0)
                .max(0.25)
                * 1000.0) as u64,
        );
        if let Some(deadline) = self.session_manager.next_deadline_ms() {
            candidates.push(deadline);
        }
        candidates.into_iter().min()
    }
}

/// Deterministic-ish jitter helper (Xorshift over a thread-local seed).
fn rand_u64() -> u64 {
    use std::cell::Cell;
    thread_local! {
        static SEED: Cell<u64> = const { Cell::new(0x9E3779B97F4A7C15) };
    }
    SEED.with(|s| {
        let mut x = s.get();
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        s.set(x);
        x
    })
}
