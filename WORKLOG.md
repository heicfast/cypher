# Cypher Worklog

Development log for the bitchat → Cypher Rust port. Newest entries at the bottom.

---

## 2026-10-30 (session 1)

### Environment
- Rust 1.99.1 (stable, 2026-10-13) installed; Android targets (aarch64/armv7/x86_64/i686) + cargo-ndk added.
- Android SDK 36 + NDK 28.2.13676358 + Gradle 9.6.1 (bitchat's pipeline) installed locally.
- Source material cloned and fully explored with 6 parallel deep-read agents:
  - `permissionlesstech/bitchat` (iOS Swift, @04995f6) — BLE engine (68 files), Noise stack, wire protocol, feature layer, docs/tests
  - `permissionlesstech/bitchat-android` (Kotlin, @20501fd) — build pipeline (AGP 9.3.1/Kotlin 2.4.10/Gradle 9.6.1), UI (Compose), BLE layer, manifest
- Both source repos are public domain (Unlicense) — porting is unencumbered.

### Decisions
- **Single-transport**: Bluetooth LE mesh only. Stripped from port: Nostr stack, Tor/Arti,
  gateway/bridge services, geohash channels, location notes, Wi-Fi Aware, hotspot.
  Message types ported: 0x01,0x02,0x03,0x04,0x10,0x11,0x20,0x21,0x22,0x23,0x24,0x25,0x26,0x27,0x29 (+0x2C parsed-only). Dropped: 0x28 nostrCarrier.
- **Architecture** mirrors bitchat `BLE-ARCHITECTURE-V3`: Rust engine owns all protocol
  state; Kotlin is a thin radio link layer (same role as CoreBluetooth on iOS).
  JNI boundary = `LinkEvent` up / `LinkCommand` down.
- **Interop**: identical GATT UUIDs (service `F47B5E2D-…-4B5C`, characteristic
  `A1B2C3D4-…-4C5D`), identical wire format, identical Noise suite — Cypher nodes appear on
  the mesh as bitchat peers.
- Crate layout: `cypher-core` (engine, pure Rust, fully unit-tested incl. golden vectors),
  `cypher-android` (JNI cdylib), `android/` app (Compose UI ported from bitchat-android).

### Verification strategy (executed)
- Golden vectors from bitchat, all replicated as Rust unit tests and **passing**:
  - `docs/courier-test-vectors.json`: 74-byte envelope TLV encoding byte-exact;
    recipientTag HMAC derivation (`ad8514c90ca1fa6b…`); the 256-byte PKCS#7-padded
    signing preimage (ttl=0, 152×`0x98`); Ed25519 seed→public-key derivation.
  - `NoiseTestVectors.json` (cacophony + snow `Noise_XX_25519_ChaChaPoly_SHA256`):
    every handshake and transport ciphertext byte-exact.
  - Private-media stable ID (`media-910bd42c…`), AuthenticatedPeerState canonical
    prefix, fanout subsetSize formula (verified against the Swift source, which differs
    from the exploration report — source of truth wins).
- Two-node **simulated mesh** (same LinkCommand/LinkEvent seam the Android BLE layer
  uses): announce exchange + link binding + peer connect, Noise XX both directions,
  private DM delivery with pending-queue drain, public broadcast exactly-once with
  self-echo + duplicate suppression, mesh ping RTT.

### Progress
- [x] Repository created: github.com/heicfast/cypher (public)
- [x] Workspace scaffold: Cargo workspace, toolchain pin, README, LICENSE, worklog
- [x] **Wire protocol layer**: BinaryProtocol v1/v2 (14/16-byte BE headers, flags,
  routes, Ed25519 signature placement), WirePayload originator-byte preservation for
  cross-client signature stability (Android-proven design), raw-DEFLATE compression
  (Apple COMPRESSION_ZLIB is raw DEFLATE — matched Java Deflater nowrap=true),
  MessagePadding buckets, PacketPayloadLimits per-type ceilings, TLV primitives, PeerID.
- [x] **Noise stack**: CipherState (12-byte nonce `0000‖u64le`, extracted-nonce transport
  framing, 1024 sliding-window replay bitmap, nonce ceiling 2³²−2, atomic rollback),
  SymmetricState (exact HKDF), HandshakeState (XX/IK/NK/X, low-order-point blacklist,
  channel-binding hash before Split), NoiseSession (+ SecureNoiseSession limits),
  NoiseRateLimiter. Validated against cacophony + snow vectors.
- [x] **Identity**: X25519 static + Ed25519 signing keys, fingerprints, peer-ID
  derivation, pluggable IdentityStore + file default.
- [x] **Payload types**: announce (+AuthenticatedPeerState), courier envelopes, prekey
  bundles, file packets (canonical v2 u32 content TLV + legacy fallbacks), GCS filters +
  RequestSync + SyncTypeFlags, private messages/read receipts/ping, group state +
  envelopes + GroupCrypto (ChaChaPoly AAD=groupID‖epoch), board posts/tombstones, voice
  bursts, vouch attestations.
- [x] **Mesh engine**: sans-I/O core (LinkEvent in / LinkCommand + EngineEvents +
  next-wake out), stream assembler, relay/flood policy, deterministic fanout,
  fragmentation planner + reassembly buffer, peer registry, topology (BFS over
  bidirectionally-confirmed edges), announce trust ladder + throttles, per-type
  handlers. Full handler set: announce/message/leave/handshake/encrypted/fragment/
  ping/pong/group/file/board/voice + unknown-type relay.
- [x] **Features layer**: courier sealing (Noise X v1 static + v2 prekey with
  domain-separated prologues) + CourierStore (tiers, quotas, spray-and-wait),
  MessageOutboxStore, LocalPrekeyStore + PrekeyBundleStore, GossipSyncManager
  (bounded stores, GCS reconciliation, request windows, response rate limiting).
- [x] **JNI bridge** (`libcypher.so`): engine lifecycle, radio-event feed, tick pump,
  send APIs, state JSON; framed command/event responses.
- [x] **Android app**: bitchat build pipeline (Gradle 9.6.1/AGP 9.3.1/Kotlin
  2.4.10/compileSdk 37/minSdk 26), BLE-only manifest (no INTERNET), Kotlin BLE link
  layer (scan/advertise/GATT client+server) driving the Rust engine, foreground service
  with 250 ms tick pump, Compose UI with the bitchat design system (palette, Geist Mono,
  tokens, peer colors), cargo-ndk integration for all 4 ABIs.
- [x] **Cross-compilation verified**: libcypher.so builds for arm64-v8a, armeabi-v7a,
  x86_64, x86 (release).
- [x] **CI**: rust.yml (fmt/clippy -D warnings/tests/cargo-ndk) + android.yml (debug APK artifact).
- [x] 181 tests green; clippy clean; rustfmt applied.

### Test counts
- cypher-core lib: 174 tests (incl. golden vectors)
- noise_vectors integration: 3 (cacophony + snow + tamper)
- simulated_mesh integration: 4 (announce/session/DM/broadcast/ping)

### Known deltas from bitchat (documented for v0.2)
- NoiseSessionManager's full quarantine/rollback state machine is simplified to the
  core session map + crossed-handshake initiation handling; the 60 s rollback cooldown
  and generation-UUID lease tracking are v0.2 items.
- Gossip sync stores exist and reconcile; engine-side sync scheduling (15/30/60 s
  rounds, initial-sync-to-new-peer) wires in v0.2.
- Private-media capability proof flow (0x20/0x09 typed payloads) delivers as
  NoisePayloadReceived events; the admission/policy gate is v0.2.
- Courier deposit/handover is store-level; engine wiring for deposit policy tiers
  arrives with the router (v0.2).
- Voice PTT inbound jitter buffer, file quota store, and board store persistence are
  v0.2 (wire formats + crypto already ported and tested).
- Wear OS module not ported (v0.3 candidate).
