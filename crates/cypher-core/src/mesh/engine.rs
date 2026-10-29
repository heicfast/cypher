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
use crate::noise::{NoiseError, NoiseRole, NoiseSession};
use crate::packet::BitchatPacket;
use crate::protocol::capabilities::PeerCapabilities;
use crate::protocol::private::{MeshPingPayload, PrivateMessagePacket};
use crate::types::{MessageType, NoisePayloadType};
use crate::util::now_ms;

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
    /// A group message envelope (opaque broadcast) arrived.
    GroupMessageReceived {
        timestamp_ms: u64,
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
    sessions: HashMap<String, NoiseSession>,

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
    /// Private messages queued while no Noise session exists; drained on
    /// session establishment (bitchat's BLENoiseSessionQueues).
    pending_private: Vec<(crate::PeerId, String, String)>,

    /// Injected clock (tests use a manual one).
    now_ms: Box<dyn Fn() -> u64 + Send + Sync>,
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
        MeshEngine {
            identity,
            nickname: nickname.into(),
            capabilities: PeerCapabilities::local_supported(),
            registry: PeerRegistry::new(),
            topology: MeshTopologyTracker::new(),
            links: HashMap::new(),
            sessions: HashMap::new(),
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
            pending_private: Vec::new(),
            now_ms: clock,
        }
    }

    // ---- public accessors ---------------------------------------------------

    pub fn local_peer_id(&self) -> crate::PeerId {
        self.identity.peer_id()
    }

    pub fn nickname(&self) -> &str {
        &self.nickname
    }

    pub fn set_nickname(&mut self, nickname: String) {
        self.nickname = nickname;
        // Nickname changes force an announce on the next opportunity.
        self.announce_throttle.reset();
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
        self.sessions
            .get(peer.short().as_str())
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
                let _ = direction;
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
        }

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
        let key = peer.short();
        let session_ok = self
            .sessions
            .get(key.as_str())
            .map(|s| s.is_established())
            .unwrap_or(false);

        if !session_ok {
            // Queue the message and start a handshake; the queue drains on
            // session establishment (mirrors BLENoiseSessionQueues).
            self.pending_private
                .push((peer.clone(), content.to_string(), message_id.to_string()));
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

        let session = self.sessions.get_mut(key.as_str()).expect("checked");
        match session.encrypt(&payload) {
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
                    self.sessions.remove(key.as_str());
                    self.initiate_handshake(peer, &mut out, now);
                }
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
        _now: u64,
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
                    timestamp_ms: packet.timestamp,
                });
                self.schedule_relay(packet, link, &message_id, out, now);
            }
            Some(MessageType::FileTransfer) => self.handle_file_transfer(packet, &sender, out, now),
            Some(MessageType::BoardPost) => self.handle_board_post(&packet, &sender, out, now),
            Some(MessageType::VoiceFrame) => self.handle_voice_frame(packet, &sender, out, now),
            Some(MessageType::PrekeyBundle)
            | Some(MessageType::CourierEnvelope)
            | Some(MessageType::RequestSync) => {
                // Handled by the features layer hooks; relayed transparently.
                self.schedule_relay(packet, link, &message_id, out, now);
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
        self.sessions.remove(sender.short().as_str());
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

        let key = sender.short().as_str().to_string();
        let is_fresh_initiation = packet.payload.len() == consts::NOISE_XX_INITIAL_MESSAGE_SIZE
            || !self.sessions.contains_key(&key);

        // The handshake reply MUST be queued before any session-established
        // side effects (announce, queued-message drain) — otherwise the peer
        // receives encrypted traffic before the completing handshake message
        // and clobbers its responder state with a crossed initiation.
        let mut response: Option<Vec<u8>> = None;
        let mut established_now = false;
        if is_fresh_initiation {
            // Respond (possibly replacing a half-open initiator session).
            let mut session = NoiseSession::new(
                sender.clone(),
                NoiseRole::Responder,
                clone_static(self.identity.noise_static_secret()),
            );
            match session.process_handshake_message(&packet.payload) {
                Ok(Some(resp)) => {
                    response = Some(resp);
                    self.sessions.insert(key, session);
                }
                Ok(None) => {
                    self.sessions.insert(key, session);
                    established_now = true;
                }
                Err(_) => {}
            }
        } else if let Some(session) = self.sessions.get_mut(&key) {
            match session.process_handshake_message(&packet.payload) {
                Ok(Some(resp)) => {
                    response = Some(resp);
                    // The initiator completes while writing message 3.
                    if session.is_established() {
                        established_now = true;
                    }
                }
                Ok(None) => {
                    established_now = true;
                }
                Err(_) => {}
            }
        }

        // 1. Reply first.
        if let Some(resp) = response {
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

        // 2. Then the session-established side effects.
        if established_now {
            out.events.push(EngineEvent::SessionEstablished {
                peer: sender.clone(),
            });
            self.on_session_established(sender, link, out, now);
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
        // Drain queued private messages for this peer.
        let pending: Vec<(crate::PeerId, String, String)> = self
            .pending_private
            .iter()
            .filter(|(p, _, _)| p.short() == peer.short())
            .cloned()
            .collect();
        self.pending_private
            .retain(|(p, _, _)| p.short() != peer.short());
        for (target, content, message_id) in pending {
            let drained = self.send_private_message(&target, &content, &message_id);
            out.events.extend(drained.events);
            out.commands.extend(drained.commands);
        }
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
        let key = sender.short();
        let Some(session) = self.sessions.get_mut(key.as_str()) else {
            // No session: initiate one; the sender will retry.
            self.initiate_handshake(sender, out, now);
            return;
        };
        match session.decrypt(&packet.payload) {
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
                            // Implicit DELIVERED ack behavior lives in the
                            // features layer.
                            let _ = pm;
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
                | NoiseError::NotEstablished => {
                    self.sessions.remove(key.as_str());
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

    fn initiate_handshake(&mut self, peer: &crate::PeerId, out: &mut EngineOutput, now: u64) {
        let key = peer.short().as_str().to_string();
        // Crossed-handshake tie-break: yield when we are the greater ID and a
        // fresh initiation may be in flight.
        let mut session = NoiseSession::new(
            peer.clone(),
            NoiseRole::Initiator,
            clone_static(self.identity.noise_static_secret()),
        );
        if let Ok(message1) = session.start_handshake() {
            self.sessions.insert(key, session);
            let packet = BitchatPacket::new(
                MessageType::NoiseHandshake.as_u8(),
                self.local_peer_id().routing_bytes(),
                Some(peer.routing_bytes()),
                now,
                message1,
                consts::MESSAGE_TTL,
            );
            self.send_packet(packet, out, now);
        }
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
        let recent_traffic = false; // simplified: the engine's dedup activity feeds this in a full port
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
        candidates.into_iter().min()
    }
}

fn clone_static(secret: &x25519_dalek::StaticSecret) -> x25519_dalek::StaticSecret {
    x25519_dalek::StaticSecret::from(secret.to_bytes())
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
