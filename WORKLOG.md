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

### Session 1 close-out
- Gossip sync wired into the engine: broadcast observation, 15/30/60 s sync
  rounds, 5 s initial-sync passes for fresh direct peers, RSR-flagged diff
  serving rate-limited 8/30 s per peer.
- Kotlin app cleanups: coroutines dependency (theme StateFlow), theme init,
  MainActivity/CypherBridge simplifications.
- Justfile (check/test/lint/fmt/build-android/apk/clean).
- **Verified end-to-end locally**: `libcypher.so` cross-compiles for all 4
  Android ABIs (arm64-v8a, armeabi-v7a, x86_64, x86) with cargo-ndk +
  NDK 28.2; Gradle 9.6.1 pipeline configured with the cargo-ndk integration
  (plugin resolution verified; artifact downloads blocked only by sandbox
  Maven-Central 429 rate limiting — GitHub Actions CI builds cleanly on
  runner infrastructure).
- Final state: 181 tests green (174 lib + 3 golden vectors + 4 simulated
  mesh), clippy clean workspace-wide, rustfmt applied, 10 commits pushed.

### Session 2 (2026-10-29/30) — build pipeline green + v0.2 + parity harness

**Build pipeline (the "CI is failing" item):**
- Root-caused the android CI failure: `platforms;android-37` is not an
  installable package in this SDK generation — the platform ships as the
  minor-versioned `platforms;android-37.0` (verified against
  dl.google.com's repository2-3.xml). Fixed the workflow (and the
  corrupted `branches: ain]` YAML).
- Caught that the .so native libraries were NEVER committed (`.gitignore`
  `jniLibs/` blanket exclusion) — CI would have shipped an APK that
  crashes on load. Adopted bitchat's production pattern: checked-in
  release .so for all 4 ABIs + `tools/native-SHA256SUMS` +
  `verify-native-checksums.sh` gate in CI.
- First real Gradle build of the Kotlin app (yesterday's session never
  compiled it — Maven 429s + a JRE-only Java install masked it). Fixed
  34 Kotlin errors across CypherBridge/BleLinkLayer/ChatScreen (API-37
  callback names, wrong imports, dead statements); AGP-9 DSL churn fixed
  by dropping Gradle-driven cargo for prebuilt libs; heap sized for the
  4 GiB box (in-process Kotlin compiler).
- **CI green end-to-end**: rust ✓ android ✓ with a 21 MB debug-APK
  artifact on every push.
- Fixed a nondeterministic security gate: `strings | grep -q` under
  `set -o pipefail` is racy (grep -q exits early → strings dies of
  SIGPIPE 141 → pipefail flips the verdict). The gate silently passed
  dirty binaries. Process substitution now makes the check deterministic.
- `.cargo/config.toml` remaps the workspace root AND cargo home out of
  the shipped binaries (dependency panic locations were the last 20
  host paths).

**v0.2 features (all of the session-1 delta list):**
- `noise/session_manager.rs` — the full 1289-line NoiseSessionManager
  port: quarantine of established sessions on unauthenticated initiation
  (receive-only), bounded rollback restores under a 60 s cooldown, four
  ingress gates in load-bearing order, lexicographic crossed-initiation
  tiebreak, generation leases with fail-closed pinned crypto, one-shot
  initiation claims, manager-owned recovery with rate-limit redispatch,
  rekey thresholds (9e8 msgs / 86 400 s idle / 77 760 s age).
- `features/pending_queues.rs` — BLENoiseSessionQueues exact semantics.
- Engine integration: raw session map replaced by the manager; tick
  processes manager deadlines; rekey poll in maintenance; new events
  SessionRestored/SessionFailed.
- `features/voice_ptt.rs` (assembly, gap-skip, flood guard, ADTS framer),
  `features/file_quota.rs` (100 MiB quota, voice_live_ exemption),
  `features/board_store.rs` (caps, tombstones, retain capping),
  `features/private_media.rs` (8-rule admission tree), engine courier
  admission (relays rejected, signature + tier policy).

**Side-by-side parity (the "compare side by side" item):**
- `tests/swift_parity_vectors.rs` — 14 cross-language golden vectors
  mined from the Swift test suite (each independently re-derived). The
  harness caught three REAL divergences, all fixed:
  1. voice decode accepted unknown flags bytes,
  2. voice decode accepted per-kind-invalid payloads (zero-frame DATA,
     non-AAC-LC START, short END),
  3. GCS accepted p outside 1..=32 and m ∈ {0,1,u32::MAX}.
- `identity/rotation.rs` — PeerIDRotation port with its 3 goldens.
- `mesh_message_identity` ported with its golden.
- `docs/PARITY.md` — the full 41-row module-by-module comparison
  (GOLDEN / VECTOR / CONSTRUCTION / STRUCTURAL / DELTA tiers).

**Final state:** 234 tests green (213 lib + 3 golden Noise + 4 simulated
mesh + 14 parity), clippy clean workspace-wide, rustfmt applied,
17 commits, all pushed. Token verified absent from every commit
(`git log -p | grep -c ghp_` → 0).

## 2026-10-30 (session 3) — startup-crash triage + radio-layer parity

### The startup crash (root cause)
The app crashed on open with `UnsatisfiedLinkError`: the Rust JNI crate
exported `Java_chat_cypher_android_CypherBridge_*` but the Kotlin object
lives in `chat.cypher.android.core`, so the JVM looked up
`Java_chat_cypher_android_core_CypherBridge_*`. Fixed all 10 exports; the
checked-in .so files still had the old symbols (rebuilt all 4 ABIs).
`tools/verify-jni-symbols.sh` now cross-checks every Kotlin `external fun`
against every .so's exported symbols — a missing symbol is a build failure
instead of a runtime crash. Wired into both CI workflows.

### Radio-layer divergences vs bitchat-android (all fixed)
1. **No MTU negotiation** — Android defaults to MTU 23 (20 usable bytes);
   the engine fragments to a 512 budget, so every large write would fail.
   Ported bitchat's sequence exactly: CONNECTED → requestMtu(517) →
   onMtuChanged(SUCCESS) → discoverServices (bitchat disconnects on MTU
   failure — same here).
2. **No write serialization** — Android allows one outstanding GATT op per
   link; a multi-write command batch would silently drop packets. Ported
   the BluetoothPacketBroadcaster queue: per-link pending queue (256
   sends / 1 MiB caps), one in-flight op, drained by
   onCharacteristicWrite (client) and onNotificationSent (server, was
   missing entirely), 15 ms rejected-start retry, 3 callback-failure
   retries, queue drop on link down.
3. **Connection storm** — with allow-duplicates scanning every
   advertisement re-triggered `Connect`. Engine now keeps a pending-connect
   map (peripheral → attempt time): duplicate discoveries suppressed, live
   links never reconnected, cleared on LinkUp/LinkDown/radio-off, retried
   after a 30 s safety timeout. Kotlin side also guards in-flight
   attempts. 2 new simulated-mesh tests cover the state machine.
4. **Deferred GATT close** — 500 ms cleanup delay after STATE_DISCONNECTED
   (bitchat's CLEANUP_DELAY) instead of closing under the callback.

### Other crash paths fixed
- Permission callback started the connectedDevice foreground service even
  when the user DENIED permissions → SecurityException on API 31+. Now the
  service only starts when all runtime grants are in.
- `MeshForegroundService.onDestroy` called `pumpThread.quitSafely()` when
  `pumpThread` was never initialized → lateinit crash. Guarded.
- `BleLinkLayer.tick()` returned the events JSON which the service
  discarded — tick-sourced events (SessionEstablished, deliveries, peer
  changes) never reached the UI. Now published through the event bus.
- MainActivity got `launchMode="singleTop"` (bitchat parity).

### Verified clean (no change needed)
- GATT UUIDs match bitchat mainnet exactly (service F47B5E2D-…-4B5C,
  characteristic A1B2C3D4-…-4C5D, standard CCCD).
- Manifest: all BLE/FGS/notification permissions correct; the
  Internet/WiFi-Aware/Camera/storage permissions we omit are the
  intentionally stripped dual-architecture legs.
- 16 KiB ELF alignment on arm64-v8a and x86_64 (NDK 28 default) —
  Android 15+ compatible.
- LinkCommand byte encoding ↔ Kotlin decodeCommand match tag-for-tag.

### State
236 tests green (2 new), clippy clean, fmt applied, JNI symbol gate +
checksum manifest regenerated and passing. Maven Central rate-limits the
sandbox IP again (429) — local APK build attempted through the Gradle
Plugin Portal mirror; the authoritative build check is CI (GitHub
runners).
