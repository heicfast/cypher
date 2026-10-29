# Cypher ⇄ bitchat Parity Map

Side-by-side comparison of every ported module against the Swift source.
Verification tiers:

* **GOLDEN** — Rust output matches a byte-level fixture pinned by the Swift
  test suite (independently re-derived). The strongest guarantee.
* **VECTOR** — matches a reference-implementation vector (cacophony/snow
  Noise vectors, RFC test cases).
* **CONSTRUCTION** — Swift pins only the *construction* (algorithm + domain
  strings + field order); no byte fixture exists anywhere. The Rust port
  implements the identical construction; identical keys ⇒ identical bytes.
* **STRUCTURAL** — Swift pins only behavioral properties (ordering, caps,
  rejection rules); the Rust port replicates the properties and is tested
  against the same scenarios.
* **DELTA** — deliberate, documented divergence (e.g. platform services
  replaced by sans-I/O host contracts).

| # | Module | Swift source | Tier | Where verified |
|---|--------|--------------|------|----------------|
| 1 | BinaryProtocol v1/v2 frames, TLV, routes, padding | `BitFoundation/BinaryProtocol.swift` | GOLDEN | `tests/fixtures/courier-test-vectors.json` (decode, byte-identical re-encode, 256-byte signing preimage); `tests/swift_parity_vectors.rs` (malformed-frame table, v2−v1=11 delta) |
| 2 | Raw-DEFLATE compression (`COMPRESSION_ZLIB` = RFC 1951, no zlib header) | `BitFoundation/CompressionUtil.swift` | GOLDEN | courier vectors include compressed round-trips |
| 3 | Noise XX/IK/NK/X stack (ChaChaPoly-SHA256) | `bitchat/Noise/NoiseProtocol.swift` | VECTOR | `tests/fixtures/NoiseTestVectors.json` — cacophony + snow vectors, `tests/noise_vectors.rs` |
| 4 | Noise session limits (24 h, 10⁹ msgs, 64 KiB, private-file budget) | `SecureNoiseSession.swift` | CONSTRUCTION | `noise/session.rs` (checks enforced in encrypt/decrypt) |
| 5 | Noise session manager (quarantine, rollback, cooldown, gates, generations, recovery) | `NoiseSessionManager.swift` (1289 lines) | STRUCTURAL | `noise/session_manager.rs` — 6 tests incl. quarantined receive-only decrypt, crossed initiation both directions, grace-period deferral, rekey atomicity; constants identical (10/20/1/60/0.2/60 s) |
| 6 | Rate limiter (10/60 s handshakes, 100/1 s messages, global 30/500) | `NoiseRateLimiter.swift` | STRUCTURAL | `noise/rate_limiter.rs` |
| 7 | PeerID (prefixes, short/full, routing bytes, fingerprints) | `BitFoundation/PeerID.swift` | GOLDEN | `swift_parity_vectors.rs` (sha256 fingerprints: `9a2db2e23f1504cd`, `7969ec0fcb8b648d`, `b6acca81a0939a85`) |
| 8 | PeerID rotation (rotation secret, rotating IDs, recognition tags, binding) | `BitFoundation/PeerIDRotation.swift` | GOLDEN | `identity/rotation.rs` — 3 goldens (`fb82dfec…`, `f7c08c528506a374`, `4568f61d61d6cbfb`) + epoch math |
| 9 | Courier envelope (TLV omission rules, tag HMAC, epoch day) | `BitFoundation/CourierEnvelope.swift` | GOLDEN | `swift_parity_vectors.rs` (tag: key `0x11×32`, day 20000 → `173d54ec4ce3de7b45d355eea6500dde`); copies-omitted-at-1 in `protocol/courier.rs` tests |
| 10 | Courier store (tiers, quotas 5/2 per depositor, 20 verified / 40 total, dedup, spray, handover) | `bitchat/Services/Courier/CourierStore.swift` | STRUCTURAL | `features/courier.rs` (spray halves budgets, verified evicted first) + engine admission (`mesh/engine.rs handle_courier`) |
| 11 | Prekey bundles + seals (prologues `bitchat-prekey-v1‖id`, `bitchat-courier-v1`) | `BitFoundation/PrekeyBundle.swift`, `NoiseEncryptionService.swift` | CONSTRUCTION | `protocol/prekey.rs`, `features/prekeys.rs`; golden prologue bytes in `swift_parity_vectors.rs` (§ prekey) — cross-path opens fail |
| 12 | Announce v1/v2 (TLVs, neighbors, capabilities, signature) | `bitchat/Protocols/Packets.swift`, `AnnounceV2Packet.swift` | CONSTRUCTION | `protocol/announce.rs`; capabilities minimal-LE canonical check |
| 13 | AuthenticatedPeerState (0x21) | `Packets.swift:150-179` | GOLDEN | `swift_parity_vectors.rs` — canonical bytes `[01 01 02 20 01]` + full rejection table |
| 14 | Private messages (NoisePayloadType 0x01, PrivateMessagePacket TLV) | `Packets.swift:182-188` | GOLDEN | `swift_parity_vectors.rs` |
| 15 | Private media admission (0x20/0x09, policy tree, waiters) | `BLEService.swift:1174-1294` | STRUCTURAL | `features/private_media.rs` — 6 tests (all 4 states, timeout→legacy, downgrade blocking, rekey invalidation) |
| 16 | Stable media IDs (`bitchat-private-media-message-v1` domain) | `BitchatFilePacket.swift:168-243` | GOLDEN | `swift_parity_vectors.rs`: `media-910bd42c65060ab76bb6406f220c4516` + direction sensitivity |
| 17 | File packets (u32 content length, legacy u64/u16 fallbacks) | `BitchatFilePacket.swift` | GOLDEN | `swift_parity_vectors.rs` (legacy 8-B fileSize, content-only fallback) |
| 18 | Mesh ping (nonce‖originTTL, hopCount) | `BitFoundation/MeshPingPayload.swift` | GOLDEN | `swift_parity_vectors.rs` |
| 19 | Mesh message identity (`SHA256(sender‖ts‖trimmed)`) | `MeshMessageIdentity.swift` | GOLDEN | `swift_parity_vectors.rs`: `b83f94d81dcdd1b0c0048f6645995dd4` |
| 20 | Voice bursts (wire format, packetizer 210-B budget, ADTS) | `VoiceBurstPacket.swift`, `PTTAudioFormat.swift:60-84` | GOLDEN | `swift_parity_vectors.rs` (START/FRAMES/END/CANCELED exact bytes, malformed table); ADTS byte-exact in `features/voice_ptt.rs` |
| 21 | Voice inbound coordinator (gap skip 0.5 s, flood guard 6 kB/s, caps) | `ChatLiveVoiceCoordinator.swift` | STRUCTURAL | `features/voice_ptt.rs` — 8 tests |
| 22 | Board wire + signing domains (`bitchat-board-v1`, `bitchat-board-del-v1`) | `BoardPackets.swift` | CONSTRUCTION | `protocol/board.rs` (domains + TLV layout identical; signature = Ed25519 over the same bytes ⇒ identical) |
| 23 | Board store (caps 5/200 posts, 5/100 orphans, skew 1 h, retain capping) | `BoardStore.swift` | STRUCTURAL | `features/board_store.rs` — 5 tests |
| 24 | Group crypto (`bitchat-group-v1` / `bitchat-group-msg-v1` domains, ChaChaPoly, roster ≤16) | `GroupProtocol.swift` | CONSTRUCTION | `protocol/group.rs` — domains + AEAD AAD identical |
| 25 | Vouch attestations (`bitchat-vouch-v1`, u8-length TLVs, batch ≤16) | `VouchAttestation.swift` | CONSTRUCTION | `protocol/vouch.rs` (lying-count clamps at 16) |
| 26 | Sync type flags (LE u64, trimmed, phantom-bit mask 0x7FF) | `SyncTypeFlags.swift` | GOLDEN | `swift_parity_vectors.rs` ([00 01], [03], [03 04], FF 07, legacy) |
| 27 | GCS filter (h64, zero-bucket→1, p 1..=32, m guards) | `GCSFilter.swift` | GOLDEN (params) / STRUCTURAL (bitstream) | `swift_parity_vectors.rs` + `protocol/sync.rs` |
| 28 | Request sync (TLV, fragment filter cap 60/100 @ ≤1024 B) | `RequestSyncPacket.swift` | STRUCTURAL | `protocol/sync.rs` |
| 29 | Gossip sync (REQUEST_SYNC rounds, RSR diffs, initial sync) | `GossipSyncManager.swift` | STRUCTURAL | `features/gossip.rs` + engine wiring; simulated-mesh tests |
| 30 | Fragmentation (13-B header, ≤10 000, reassembly, resync) | `BLEFragmentAssemblyBuffer.swift`, `Fragmentation.swift` | STRUCTURAL | `mesh/fragmentation.rs` |
| 31 | Dedup + relay policy + fanout (bits(count−1)+1) | `MessageDeduplicationService.swift`, `BLEFanoutSelector.swift` | STRUCTURAL | `mesh/dedup.rs`, `mesh/fanout.rs` (fanout formula verified against Swift source) |
| 32 | Incoming file quota (100 MiB, oldest-first eviction, `voice_live_` exempt, 7-day retention) | `BLEIncomingFileStore.swift` | STRUCTURAL | `features/file_quota.rs` — 5 tests |
| 33 | Message padding buckets (256/512/1024/2048, +16 headroom) | `MessagePadding.swift` | STRUCTURAL | `padding.rs` |
| 34 | Payload limits per type (incl. unknown 128 KiB, file/Noise 1.18 MB) | `PacketPayloadLimits.swift`, `FileTransferLimits.swift` | GOLDEN | `protocol/limits.rs` (values byte-identical, from source) |
| 35 | Identity (X25519 + Ed25519, keychain persistence contract) | `SecureIdentityStateManager.swift` | CONSTRUCTION | `identity/mod.rs`; persistence = host contract (DELTA: Android Keystore via JNI) |
| 36 | Hex codec (0x prefix, mixed case, signed-pair rejection) | `Data+Hex.swift` | GOLDEN | `util.rs` (`DataHexTests` contract) |
| 37 | HKDF (RFC 5869) / HMAC-SHA256 | CryptoKit usage | VECTOR | RFC 5869 case 1 in `noise/mod.rs`; rotation goldens |
| 38 | Engine (announce eval, dedup, topology, split-horizon relay, link auth) | `BLEService.swift` engine parts | STRUCTURAL | `mesh/engine.rs`; 4 simulated-mesh tests (announce→session→DM→broadcast→ping) |
| 39 | JNI bridge (LinkEvent↑/LinkCommand↓) | CoreBluetooth layer | DELTA | by design: Kotlin BLE replaces CoreBluetooth; same engine-side contract |
| 40 | Noise session queues (FIFO, one-shot drain, prepend-on-failure) | `BLENoiseSessionQueues.swift` | STRUCTURAL | `features/pending_queues.rs` — 4 tests |
| 41 | BLE reconnect throttle (60 s per epoch) | `BLENoiseReconnectPolicy.swift` | STRUCTURAL | rolled into session-manager cooldown tests |

## Deltas (deliberate, by project scope)

1. **Single transport**: Nostr/Tor/bridge/gateway/geohash/WiFi-Aware are
   excluded entirely (project scope: Bluetooth mesh only).
2. **Sans-I/O core**: timers, filesystem, audio output, and notifications
   are host contracts (`handle_deadlines`, `QuotaActions`, `VoiceEvent`,
   JNI event stream) — same behavior, no runtime dependency.
3. **Persistence**: iOS Keychain/FileProtection → host-supplied stores
   (`IdentityStore`, JSON records for board/courier); identical formats.
4. **Wear OS**: not ported (v0.3 candidate; upstream has a separate Wear
   module).
5. **Reproducible builds**: bitchat uses a Docker container for byte-
   reproducible release builds; Cypher pins checksums
   (`tools/native-SHA256SUMS`) + path-remapped (`--remap-path-prefix`)
   toolchain builds instead. Functionally equivalent provenance.

## Not pinned by any fixture (upstream has none) — construction-identical

Board/group/vouch *signature bytes* (fixed keys ⇒ deterministic Ed25519,
identical signable-bytes construction), GCS Golomb-Rice *bitstream bytes*
(structural properties only), exact DEFLATE output for arbitrary payloads
(zlib behavior, verified via round-trips and the golden courier vectors).
