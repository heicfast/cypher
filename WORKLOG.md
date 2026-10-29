# Cypher Worklog

Development log for the bitchat → Cypher Rust port. Newest entries at the bottom.

---

## 2026-10-30 (session 1)

### Environment
- Rust 1.99.1 (stable, 2026-10-13) installed; Android targets (aarch64/armv7/x86_64/i686) + cargo-ndk added.
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

### Progress
- [x] Repository created: github.com/heicfast/cypher (public)
- [x] Workspace scaffold: Cargo workspace, toolchain pin, README, LICENSE, worklog
- [ ] cypher-core: wire protocol (BinaryProtocol v1/v2, TLV packets, padding, zlib)
- [ ] cypher-core: Noise stack (XX/X handshakes, transport framing, replay window, session manager)
- [ ] cypher-core: identity (PeerID, fingerprints, keys)
- [ ] cypher-core: mesh engine (dedup, relay, fragmentation, announce, topology, sync)
- [ ] cypher-core: features (courier, prekeys, groups, board, files, voice)
- [ ] cypher-android: JNI bridge
- [ ] android: app module (Compose UI + BLE link layer + foreground service)
- [ ] CI: cargo test/clippy + gradle build

### Verification strategy
- Golden vectors from bitchat: `docs/courier-test-vectors.json` (envelope TLV, recipient tag,
  packet signing preimage w/ PKCS#7 padding), `NoiseTestVectors.json` (cacophony + snow XX
  vectors), media stable-ID vector, announce/fragment format tests — all replicated as Rust
  unit tests.
