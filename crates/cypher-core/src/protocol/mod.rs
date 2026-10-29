//! Payload packet types (TLV structures carried inside `BitchatPacket`
//! payloads) and their size ceilings.

pub mod announce;
pub mod board;
pub mod capabilities;
pub mod courier;
pub mod file;
pub mod group;
pub mod limits;
pub mod prekey;
pub mod private;
pub mod sync;
pub mod voice;
pub mod vouch;
