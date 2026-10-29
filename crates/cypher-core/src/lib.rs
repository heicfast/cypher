//! # cypher-core
//!
//! Platform-free mesh core for Cypher — a Rust port of the bitchat Bluetooth LE
//! peer-to-peer messaging stack.
//!
//! Layering (mirrors bitchat's `BLE-ARCHITECTURE-V3`):
//!
//! * [`protocol`] — binary wire format (v1/v2 frames), TLV packet payloads,
//!   padding, compression, payload limits. Byte-compatible with bitchat.
//! * [`noise`] — Noise protocol stack: `Noise_XX_25519_ChaChaPoly_SHA256`
//!   interactive sessions, one-way Noise `X` seals for store-and-forward,
//!   transport framing with explicit nonces + sliding-window replay protection.
//! * [`identity`] — device identity: X25519 static key, Ed25519 signing key,
//!   fingerprints, on-air 8-byte peer IDs.
//! * [`mesh`] — the mesh engine: link events, ingress attribution, dedup,
//!   relay/flood control, fanout selection, fragmentation/reassembly, peer
//!   registry, topology tracking, announce handling, gossip sync.
//! * [`features`] — store-and-forward features riding the mesh: courier mail,
//!   prekeys, groups, board, file transfer, voice bursts.
//!
//! Everything in this crate is I/O-free and platform-free: the host application
//! owns the radio (Android BLE in Kotlin, or a simulator in tests) and exchanges
//! [`mesh::link::LinkEvent`] / [`mesh::link::LinkCommand`] with the engine.

pub mod compression;
pub mod consts;
pub mod features;
pub mod identity;
pub mod mesh;
pub mod noise;
pub mod packet;
pub mod padding;
pub mod peer_id;
pub mod protocol;
pub mod tlv;
pub mod types;
pub mod util;

#[cfg(test)]
pub mod testutil;

pub use packet::BitchatPacket;
pub use peer_id::PeerId;
pub use types::MessageType;

// Re-export the payload-limit helpers where callers expect them.
pub use protocol::limits;
