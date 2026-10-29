//! Feature layers riding the mesh: courier mail, prekeys, gossip sync.

pub mod courier;
pub mod gossip;
pub mod pending_queues;
pub mod prekeys;

pub use courier::{CourierStore, CourierTier, MessageOutboxStore, QueuedMessage};
pub use gossip::GossipSyncManager;
pub use pending_queues::PendingQueues;
pub use prekeys::{LocalPrekeyStore, PrekeyBundleStore};
