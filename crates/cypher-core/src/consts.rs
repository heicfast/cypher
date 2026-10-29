//! Engine constants — the behavioural contract of the mesh.
//! Every value mirrors bitchat's `TransportConfig` / policy files exactly.

// ---------------------------------------------------------------------------
// Protocol
// ---------------------------------------------------------------------------
/// Default hop limit for originated packets.
pub const MESSAGE_TTL: u8 = 7;
/// Timestamp skew tolerance for live packets (ms).
pub const BLE_MAX_TIMESTAMP_SKEW_MS: u64 = 120_000;
/// Public broadcast messages stay sync-able (and acceptable) this long.
pub const SYNC_PUBLIC_MESSAGE_MAX_AGE_S: u64 = 6 * 3600;
/// Default BLE MTU budget used for chunk sizing.
pub const BLE_MAX_MTU: usize = 512;
/// Default fragment chunk size (~512 MTU minus protocol overhead).
pub const BLE_DEFAULT_FRAGMENT_SIZE: usize = 469;
/// Minimum fragment chunk size.
pub const FRAGMENT_MIN_CHUNK: usize = 64;
/// Directed raw fileTransfer must fit this many fragments (Android compat).
pub const PRIVATE_MEDIA_V1_MAX_FRAGMENTS: u16 = 256;
/// Max fragments accepted per reassembly stream.
pub const FRAGMENT_MAX_TOTAL: u16 = 10_000;
/// Max concurrent fragment reassemblies.
pub const BLE_MAX_IN_FLIGHT_ASSEMBLIES: usize = 128;
/// Reassembly buffer lifetime.
pub const BLE_FRAGMENT_LIFETIME_S: u64 = 30;
/// Broadcast fragment stall threshold before targeted REQUEST_SYNC.
pub const BLE_FRAGMENT_RESYNC_STALL_S: u64 = 5;
/// Per-stream resync retry spacing.
pub const BLE_FRAGMENT_RESYNC_RETRY_S: u64 = 10;
/// Fragment pacing, broadcast.
pub const BLE_FRAGMENT_SPACING_MS: u64 = 30;
/// Fragment pacing, directed.
pub const BLE_FRAGMENT_SPACING_DIRECTED_MS: u64 = 25;
/// Degree at which the graph counts as "dense".
pub const BLE_HIGH_DEGREE_THRESHOLD: usize = 6;
/// Max concurrent outbound fragmented transfers.
pub const BLE_MAX_CONCURRENT_TRANSFERS: usize = 2;

// ---------------------------------------------------------------------------
// Relay / flood control
// ---------------------------------------------------------------------------
pub const BLE_FRAGMENT_RELAY_MIN_DELAY_MS: u64 = 8;
pub const BLE_FRAGMENT_RELAY_MAX_DELAY_MS: u64 = 25;

// ---------------------------------------------------------------------------
// Connection / scan
// ---------------------------------------------------------------------------
pub const BLE_MAX_CENTRAL_LINKS: usize = 6;
pub const BLE_CONNECT_RATE_LIMIT_S: f64 = 0.5;
pub const BLE_CONNECT_TIMEOUT_S: f64 = 8.0;
pub const BLE_CANDIDATES_MAX: usize = 100;
pub const BLE_WEAK_LINK_COOLDOWN_S: f64 = 30.0;
pub const BLE_WEAK_LINK_RSSI_CUTOFF: i32 = -90;
pub const BLE_RSSI_DEFAULT: i32 = -90;
pub const BLE_RSSI_CONNECTED: i32 = -85;
pub const BLE_RSSI_ISOLATED_BASE: i32 = -95;
pub const BLE_RSSI_ISOLATED_RELAXED: i32 = -100;
pub const BLE_ISOLATION_RELAX_S: f64 = 30.0;
pub const BLE_DUTY_ON_S: f64 = 5.0;
pub const BLE_DUTY_OFF_S: f64 = 10.0;
pub const BLE_DUTY_ON_S_DENSE: f64 = 3.0;
pub const BLE_DUTY_OFF_S_DENSE: f64 = 15.0;
pub const BLE_RECENT_TRAFFIC_FORCE_SCAN_S: f64 = 10.0;
pub const BLE_TIMEOUT_DISCOVERY_IGNORE_S: f64 = 15.0;
pub const BLE_DISCONNECT_DISCOVERY_IGNORE_S: f64 = 3.0;
pub const BLE_CONNECT_TIMEOUT_BACKOFF_WINDOW_S: f64 = 120.0;
pub const BLE_RESTART_SCAN_DELAY_S: f64 = 0.1;
pub const BLE_PENDING_WRITE_CAP_BYTES: usize = 1_000_000;
pub const BLE_NOTIFICATIONS_CAP_COUNT: usize = 128;
pub const BLE_NOTIFICATION_RETRY_DELAY_MS: u64 = 25;
pub const BLE_NOTIFICATION_RETRY_MAX_ATTEMPTS: u32 = 80;
pub const BLE_BACKPRESSURE_LOG_INTERVAL: u32 = 25;
pub const BLE_ASSEMBLER_HARD_CAP_BYTES: usize = 8 * 1024 * 1024;
pub const BLE_ASSEMBLER_STALL_RESET_MS: u64 = 250;

// ---------------------------------------------------------------------------
// Maintenance / presence
// ---------------------------------------------------------------------------
pub const BLE_MAINTENANCE_INTERVAL_S: f64 = 5.0;
pub const BLE_MAINTENANCE_LEEWAY_S: f64 = 1.0;
pub const BLE_ANNOUNCE_INTERVAL_ISOLATED_S: f64 = 4.0;
pub const BLE_ANNOUNCE_INTERVAL_CONNECTED_SPARSE_S: f64 = 15.0;
pub const BLE_ANNOUNCE_INTERVAL_CONNECTED_DENSE_S: f64 = 30.0;
pub const BLE_ANNOUNCE_JITTER_SPARSE_S: f64 = 4.0;
pub const BLE_ANNOUNCE_JITTER_DENSE_S: f64 = 8.0;
pub const BLE_ANNOUNCE_MIN_INTERVAL_S: f64 = 1.0;
pub const BLE_FORCE_ANNOUNCE_MIN_INTERVAL_S: f64 = 0.15;
pub const BLE_PEER_INACTIVITY_TIMEOUT_S: f64 = 8.0;
pub const BLE_PEER_RETENTION_VERIFIED_S: f64 = 60.0;
pub const BLE_PEER_RETENTION_UNVERIFIED_S: f64 = 45.0;
pub const BLE_LINK_REBIND_COOLDOWN_S: f64 = 60.0;
pub const BLE_POST_SUBSCRIBE_ANNOUNCE_DELAY_S: f64 = 0.05;
pub const BLE_POST_ANNOUNCE_DELAY_S: f64 = 0.4;
pub const BLE_INITIAL_ANNOUNCE_DELAY_S: f64 = 0.6;
pub const BLE_SERVICE_DISCOVERY_RETRY_S: f64 = 0.5;
pub const BLE_EXPECTED_WRITE_PER_FRAGMENT_MS: u64 = 20;
pub const BLE_EXPECTED_WRITE_MAX_MS: u64 = 5000;
pub const BLE_AFTERGLOW_MIN_S: f64 = 0.3;
pub const BLE_AFTERGLOW_MAX_S: f64 = 0.6;
pub const BLE_INITIAL_SYNC_DELAY_S: f64 = 1.0;
pub const BLE_RECONNECT_LOG_DEBOUNCE_S: f64 = 2.0;
pub const BLE_DISCONNECT_NOTIFY_DEBOUNCE_S: f64 = 0.9;

// BCH-01-004 subscription announce limiter
pub const BLE_SUBSCRIPTION_RATE_LIMIT_MIN_S: f64 = 2.0;
pub const BLE_SUBSCRIPTION_RATE_LIMIT_BACKOFF_FACTOR: f64 = 2.0;
pub const BLE_SUBSCRIPTION_RATE_LIMIT_MAX_BACKOFF_S: f64 = 30.0;
pub const BLE_SUBSCRIPTION_RATE_LIMIT_WINDOW_S: f64 = 60.0;
pub const BLE_SUBSCRIPTION_RATE_LIMIT_MAX_ATTEMPTS: u32 = 5;

// ---------------------------------------------------------------------------
// Announce freshness
// ---------------------------------------------------------------------------
pub const BLE_ANNOUNCE_MAX_AGE_S: u64 = 900;

// ---------------------------------------------------------------------------
// Source routing (v2)
// ---------------------------------------------------------------------------
pub const BLE_SOURCE_ROUTE_MAX_INTERMEDIATE_HOPS: usize = 4;
pub const BLE_SOURCE_ROUTE_CONFIRMATION_WINDOW_S: f64 = 10.0;
pub const BLE_SOURCE_ROUTE_SUPPRESSION_S: f64 = 60.0;
pub const TOPOLOGY_FRESHNESS_S: u64 = 60;
pub const TOPOLOGY_MAX_NEIGHBORS_PER_ANNOUNCE: usize = 10;
pub const TOPOLOGY_MAX_ROUTE_HOPS: usize = 10;

// ---------------------------------------------------------------------------
// Dedup
// ---------------------------------------------------------------------------
pub const DEDUP_MAX_AGE_S: u64 = 300;
pub const DEDUP_MAX_COUNT: usize = 1000;
/// LRU trims to 75% of capacity on overflow.
pub const DEDUP_TRIM_FACTOR: f64 = 0.75;
/// Ingress-link duplicate suppression lifetime.
pub const BLE_INGRESS_RECORD_LIFETIME_S: f64 = 3.0;

// ---------------------------------------------------------------------------
// Directed relay spool
// ---------------------------------------------------------------------------
pub const BLE_DIRECTED_SPOOL_WINDOW_S: f64 = 60.0;
pub const BLE_DIRECTED_SPOOL_CAPACITY: usize = 512;
pub const BLE_DIRECTED_SPOOL_MAX_BYTES: usize = 2 * 1024 * 1024;

// ---------------------------------------------------------------------------
// Ping
// ---------------------------------------------------------------------------
pub const MESH_PING_TIMEOUT_S: f64 = 10.0;
pub const MESH_PING_INBOUND_MAX_PER_LINK: usize = 5;
pub const MESH_PING_INBOUND_WINDOW_S: u64 = 10;

// ---------------------------------------------------------------------------
// Sync / gossip
// ---------------------------------------------------------------------------
pub const SYNC_GCS_MAX_BYTES: usize = 400;
pub const SYNC_TARGET_FPR: f64 = 0.01;
pub const SYNC_GCS_MAX_P: u8 = 32;
pub const SYNC_SEEN_CAPACITY: usize = 1000;
pub const SYNC_MAX_MESSAGE_AGE_S: u64 = 900;
pub const SYNC_RESPONSE_WINDOW_S: u64 = 30;
pub const SYNC_RESPONSE_MAX_PER_WINDOW: usize = 8;
pub const SYNC_RESPONSE_WINDOW_SLICE_S: u64 = 30;
pub const SYNC_INTERVAL_MESSAGES_S: u64 = 15;
pub const SYNC_INTERVAL_FRAGMENTS_S: u64 = 30;
pub const SYNC_INTERVAL_FILES_S: u64 = 60;
pub const SYNC_INTERVAL_PREKEYS_S: u64 = 60;
pub const SYNC_INTERVAL_BOARD_S: u64 = 60;
pub const SYNC_MAINTENANCE_S: u64 = 30;
pub const SYNC_STALE_PEER_S: u64 = 60;
pub const SYNC_STALE_PEER_TIMEOUT_S: u64 = 60;
pub const SYNC_STORE_MESSAGES: usize = 1000;
pub const SYNC_STORE_MESSAGES_BYTES: usize = 8 * 1024 * 1024;
pub const SYNC_STORE_FRAGMENTS: usize = 600;
pub const SYNC_STORE_FRAGMENTS_BYTES: usize = 1024 * 1024;
pub const SYNC_STORE_FILES: usize = 200;
pub const SYNC_STORE_FILES_BYTES: usize = 32 * 1024 * 1024;
pub const SYNC_STORE_GROUP_MESSAGES: usize = 200;
pub const SYNC_STORE_GROUP_MESSAGES_BYTES: usize = 4 * 1024 * 1024;
pub const SYNC_STORE_PREKEYS: usize = 200;
pub const SYNC_INITIAL_SYNC_DELAY_S: u64 = 5;
pub const REQUEST_SYNC_MAX_FRAGMENT_FILTER: usize = 60;
pub const REQUEST_SYNC_MAX_ENCODED_BYTES: usize = 1024;

// ---------------------------------------------------------------------------
// Courier / store-and-forward
// ---------------------------------------------------------------------------
pub const COURIER_TAG_LENGTH: usize = 16;
pub const COURIER_TAG_CONTEXT: &str = "bitchat-courier-tag-v1";
pub const COURIER_MAX_CIPHERTEXT_BYTES: usize = 16 * 1024;
pub const COURIER_MAX_LIFETIME_S: u64 = 24 * 3600;
pub const COURIER_MAX_COPIES: u8 = 8;
pub const COURIER_INITIAL_COPIES: u8 = 4;
pub const COURIER_EXPIRY_SLACK_S: u64 = 3600;
pub const COURIER_STORE_MAX: usize = 40;
pub const COURIER_STORE_MAX_VERIFIED: usize = 20;
pub const COURIER_MAX_PER_FAVORITE_DEPOSITOR: usize = 5;
pub const COURIER_MAX_PER_VERIFIED_DEPOSITOR: usize = 2;
pub const COURIER_OPENED_ID_CAP: usize = 512;
pub const COURIER_REMOTE_HANDOVER_COOLDOWN_S: u64 = 600;
pub const OUTBOX_MAX_PER_PEER: usize = 100;
pub const OUTBOX_TTL_S: u64 = 24 * 3600;
pub const OUTBOX_MAX_SEND_ATTEMPTS: usize = 8;
pub const ROUTER_MAX_COURIERS_PER_MESSAGE: usize = 3;
pub const ROUTER_BRIDGE_SWEEP_S: u64 = 120;

// ---------------------------------------------------------------------------
// Prekeys
// ---------------------------------------------------------------------------
pub const PREKEY_BATCH_SIZE: usize = 8;
pub const PREKEY_REPLENISH_THRESHOLD: usize = 3;
pub const PREKEY_CONSUMED_GRACE_S: u64 = 48 * 3600;
pub const PREKEY_UNCONSUMED_RETENTION_S: u64 = 30 * 24 * 3600;
pub const PREKEY_MAX_BUNDLE_AGE_FOR_SEALING_S: u64 = 7 * 24 * 3600;
pub const PREKEY_STORE_MAX_PEERS: usize = 200;
pub const PREKEY_REBROADCAST_S: u64 = 3600;
pub const PREKEY_FRESHNESS_S: u64 = 24 * 3600;

// ---------------------------------------------------------------------------
// Groups
// ---------------------------------------------------------------------------
pub const GROUP_ID_LENGTH: usize = 16;
pub const GROUP_KEY_LENGTH: usize = 32;
pub const GROUP_MAX_MEMBERS: usize = 16;
pub const GROUP_NICKNAME_MAX_BYTES: usize = 64;

// ---------------------------------------------------------------------------
// Board
// ---------------------------------------------------------------------------
pub const BOARD_MAX_POSTS: usize = 200;
pub const BOARD_MAX_PER_AUTHOR: usize = 5;
pub const BOARD_MAX_CONTENT_BYTES: usize = 512;
pub const BOARD_MAX_LIFETIME_S: u64 = 7 * 24 * 3600;
pub const BOARD_GEOHASH_MAX_CHARS: usize = 12;
pub const BOARD_ORPHAN_TOMBSTONES_MAX: usize = 100;
pub const BOARD_ORPHAN_PER_AUTHOR_MAX: usize = 5;
pub const BOARD_CLOCK_SKEW_S: u64 = 3600;
pub const BOARD_URGENT_ALERT_WINDOW_S: u64 = 1800;

// ---------------------------------------------------------------------------
// Files / media
// ---------------------------------------------------------------------------
pub const FILE_MAX_PAYLOAD_BYTES: usize = 1024 * 1024; // 1 MiB
pub const FILE_MAX_VOICE_BYTES: usize = 512 * 1024;
pub const FILE_MAX_IMAGE_BYTES: usize = 512 * 1024;
/// 1 MiB + TLV envelope (18 + 2*65535) + binary envelope overhead.
pub const FILE_MAX_FRAMED_BYTES: usize =
    1024 * 1024 + 18 + 2 * 65535 + 16 + 8 + 8 + 64;

// ---------------------------------------------------------------------------
// Push-to-talk voice
// ---------------------------------------------------------------------------
pub const PTT_MAX_BURST_CONTENT_BYTES: usize = 210;
pub const PTT_JITTER_BUFFER_S: f64 = 0.35;
pub const PTT_DEADLINE_S: f64 = 0.5;
pub const PTT_BURST_END_TIMEOUT_S: f64 = 3.0;
pub const PTT_MAX_CONCURRENT_ASSEMBLIES: usize = 8;
pub const PTT_MAX_BURST_BYTES: usize = 384 * 1024;
pub const PTT_FINISHED_REGISTRY_S: u64 = 600;
pub const PTT_INBOUND_FLOOD_GUARD_BYTES_PER_S: usize = 6000;
pub const PTT_PUBLIC_FRAME_MAX_AGE_S: u64 = 30;

// ---------------------------------------------------------------------------
// Noise security
// ---------------------------------------------------------------------------
pub const NOISE_MAX_MESSAGE_SIZE: usize = 65_535;
pub const NOISE_TRANSPORT_OVERHEAD: usize = 20;
pub const NOISE_MAX_HANDSHAKE_MESSAGE_SIZE: usize = 2048;
pub const NOISE_XX_INITIAL_MESSAGE_SIZE: usize = 32;
pub const NOISE_ORDINARY_HANDSHAKE_TIMEOUT_S: f64 = 10.0;
pub const NOISE_RESPONDER_HANDSHAKE_TIMEOUT_S: f64 = 20.0;
pub const NOISE_COLLISION_RECOVERY_DELAY_S: f64 = 0.2;
pub const NOISE_RATE_LIMIT_RECOVERY_DELAY_S: f64 = 60.0;
pub const NOISE_RECENT_INITIATOR_GRACE_S: f64 = 1.0;
pub const NOISE_RECONNECT_ROLLBACK_COOLDOWN_S: f64 = 60.0;
pub const NOISE_SESSION_TIMEOUT_S: f64 = 86_400.0;
pub const NOISE_MAX_MESSAGES_PER_SESSION: u64 = 1_000_000_000;
pub const NOISE_REKEY_CHECK_INTERVAL_S: f64 = 60.0;
pub const NOISE_REKEY_MESSAGE_THRESHOLD: u64 = 900_000_000;
pub const NOISE_REKEY_INACTIVITY_S: f64 = 86_400.0;
pub const NOISE_REKEY_SESSION_AGE_S: f64 = 77_760.0;
pub const NOISE_MAX_HANDSHAKES_PER_MIN_PER_PEER: usize = 10;
pub const NOISE_MAX_MESSAGES_PER_S_PER_PEER: usize = 100;
pub const NOISE_MAX_GLOBAL_HANDSHAKES_PER_MIN: usize = 30;
pub const NOISE_MAX_GLOBAL_MESSAGES_PER_S: usize = 500;
pub const NOISE_REPLAY_WINDOW: u64 = 1024;
/// `FILE_MAX_FRAMED_BYTES` + transport ciphertext overhead + outer overhead.
pub const NOISE_MAX_PRIVATE_FILE_PLAINTEXT: usize = FILE_MAX_FRAMED_BYTES - 32 - 20;
pub const NOISE_MAX_PRIVATE_FILE_CIPHERTEXT: usize = NOISE_MAX_PRIVATE_FILE_PLAINTEXT + 20;

// ---------------------------------------------------------------------------
// Private media sessions
// ---------------------------------------------------------------------------
pub const PRIVATE_MEDIA_PROOF_TIMEOUT_S: f64 = 5.0;
pub const PRIVATE_MEDIA_PENDING_PEER_CAP: usize = 64;
pub const PRIVATE_MEDIA_WAITERS_PER_PEER: usize = 16;
pub const PRIVATE_MEDIA_LEDGER_CAPACITY: usize = 4096;
pub const PRIVATE_MEDIA_LEDGER_TTL_S: u64 = 7 * 24 * 3600;
pub const PRIVATE_MEDIA_ADMISSION_ACTIVE: usize = 512;
pub const PRIVATE_MEDIA_ADMISSION_TOMBSTONES: usize = 512;
pub const PRIVATE_MEDIA_ADMISSION_LIFETIME_S: u64 = 3600;

// ---------------------------------------------------------------------------
// Nickname / content input limits
// ---------------------------------------------------------------------------
pub const NICKNAME_MAX_CHARS: usize = 50;
pub const MESSAGE_MAX_CHARS: usize = 60_000;
pub const READ_RECEIPT_TIMESTAMP_TOLERANCE_S: i64 = 300;

// ---------------------------------------------------------------------------
// Timelines
// ---------------------------------------------------------------------------
pub const TIMELINE_CAP: usize = 1337;
pub const CONTENT_LRU_CAP: usize = 2000;
