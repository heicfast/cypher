//! The radio link boundary — the exact role CoreBluetooth plays on iOS.
//!
//! The host (Android Kotlin, or a test simulator) owns the radio and exchanges
//! these types with the engine. `LinkEvent` flows up into the engine;
//! `LinkCommand` flows down to the radio. The engine knows nothing about
//! platform Bluetooth APIs.

use crate::packet::BitchatPacket;

/// Opaque link identifier (peripheral UUID / GATT server connection id).
pub type LinkId = u64;
/// Opaque discovered-peripheral identifier.
pub type PeripheralId = u64;

/// Which side of the GATT link we are.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum LinkDirection {
    /// We are the central (client): we scanned, connected, and write to the
    /// peer's characteristic, receiving notifications.
    Central,
    /// We are the peripheral (server): the peer connected and subscribed;
    /// we notify them.
    Peripheral,
}

/// Bluetooth adapter state (mirrors the CBManagerState surface).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum RadioState {
    Unknown,
    Resetting,
    Unsupported,
    Unauthorized,
    PoweredOff,
    PoweredOn,
}

/// Events from the radio into the engine.
#[derive(Clone, Debug)]
pub enum LinkEvent {
    /// Raw bytes arrived on a link (notification on a central link, or a
    /// write on a peripheral link). The engine owns stream reassembly.
    BytesReceived {
        link: LinkId,
        direction: LinkDirection,
        bytes: Vec<u8>,
    },
    /// A link's transport became usable (connected + subscribed / subscribed
    /// to us). The engine responds with an announce.
    LinkUp {
        link: LinkId,
        direction: LinkDirection,
    },
    /// A link went down (disconnect / unsubscribe).
    LinkDown {
        link: LinkId,
        direction: LinkDirection,
    },
    /// Radio adapter state changed.
    RadioStateChanged(RadioState),
    /// Scan discovery: a peripheral advertising our service UUID.
    Discovered {
        peripheral: PeripheralId,
        rssi: i32,
        connectable: bool,
    },
    /// A central connection attempt succeeded (service discovery is the
    /// host's responsibility; emit `LinkUp` once subscribed).
    Connected { peripheral: PeripheralId },
    /// A central connection attempt failed.
    ConnectionFailed { peripheral: PeripheralId },
    /// A peripheral link disconnected before `LinkUp`.
    Disconnected { peripheral: PeripheralId },
    /// The write-without-response queue for a link drained (flow control).
    LinkWritable { link: LinkId },
    /// Maximum write payload the link accepts (MTU minus ATT overhead).
    LinkWriteLimit { link: LinkId, limit: usize },
    /// A peripheral-role central subscribed or wrote to us.
    CentralSubscribed { link: LinkId },
}

/// Commands from the engine to the radio.
#[derive(Clone, Debug, PartialEq)]
pub enum LinkCommand {
    /// Start scanning for our service UUID. `allow_duplicates` enables
    /// continuous discovery while the app is foregrounded.
    StartScan { allow_duplicates: bool },
    StopScan,
    /// Start/stop advertising the service (advertising payload is the
    /// service UUID only — maximum privacy, exactly like bitchat).
    StartAdvertising,
    StopAdvertising,
    /// Central-role connection management.
    Connect { peripheral: PeripheralId },
    Disconnect { peripheral: PeripheralId },
    /// Write bytes on a link (write-without-response on central links;
    /// notify on peripheral links). The radio applies per-link backpressure.
    Write {
        link: LinkId,
        direction: LinkDirection,
        bytes: Vec<u8>,
        priority: WritePriority,
    },
}

/// Outbound write priorities (lower sorts first) — mirrors bitchat's
/// `BLEOutboundWritePriority`.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug)]
pub enum WritePriority {
    /// AnnounceV2 and most control traffic.
    High,
    /// Fragment trains: (1, clamp(total, 1, 65535)).
    Fragment(u16),
    /// File transfers.
    FileTransfer,
    /// Everything else.
    Low,
}

impl LinkCommand {
    /// Serialize a command for the JNI bridge (tag-prefixed binary).
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::new();
        match self {
            LinkCommand::StartScan { allow_duplicates } => {
                out.push(0x01);
                out.push(*allow_duplicates as u8);
            }
            LinkCommand::StopScan => out.push(0x02),
            LinkCommand::StartAdvertising => out.push(0x03),
            LinkCommand::StopAdvertising => out.push(0x04),
            LinkCommand::Connect { peripheral } => {
                out.push(0x05);
                out.extend_from_slice(&peripheral.to_le_bytes());
            }
            LinkCommand::Disconnect { peripheral } => {
                out.push(0x06);
                out.extend_from_slice(&peripheral.to_le_bytes());
            }
            LinkCommand::Write { link, direction, bytes, priority } => {
                out.push(0x07);
                out.extend_from_slice(&link.to_le_bytes());
                out.push(match direction {
                    LinkDirection::Central => 0,
                    LinkDirection::Peripheral => 1,
                });
                out.push(match priority {
                    WritePriority::High => 0,
                    WritePriority::Fragment(_) => 1,
                    WritePriority::FileTransfer => 2,
                    WritePriority::Low => 3,
                });
                out.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
                out.extend_from_slice(bytes);
            }
        }
        out
    }
}

/// A decoded frame event emitted by the stream assembler.
#[derive(Clone, Debug)]
pub struct DecodedFrame {
    pub packet: BitchatPacket,
    pub link: LinkId,
    pub direction: LinkDirection,
}
