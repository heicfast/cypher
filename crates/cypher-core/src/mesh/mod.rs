//! The mesh engine — link layer, policies, and the sans-I/O core.

pub mod announce;
pub mod dedup;
pub mod engine;
pub mod fanout;
pub mod fragmentation;
pub mod link;
pub mod peer_registry;
pub mod relay;
pub mod stream;
pub mod topology;

pub use engine::{EngineEvent, EngineOutput, MeshEngine};
pub use link::{
    LinkCommand, LinkDirection, LinkEvent, LinkId, LinkId as PeripheralId, RadioState,
    WritePriority,
};
