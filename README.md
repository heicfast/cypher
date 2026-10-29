# Cypher

**Bluetooth LE decentralized peer-to-peer mesh messaging for Android.**

Cypher is a Rust port of the [bitchat](https://github.com/permissionlesstech/bitchat) mesh core:
the same wire protocol, the same Noise-encrypted sessions, the same store-and-forward mesh
architecture — reimplemented in Rust for production use on Android.

- **Single transport**: Bluetooth LE mesh only. No internet relays, no Nostr, no Tor.
- **Same protocol bytes**: Cypher speaks the bitchat binary mesh protocol (v1/v2 frames,
  `Noise_XX_25519_ChaChaPoly_SHA256`, Ed25519 packet signatures) and interoperates with
  bitchat devices on the mesh.
- **Same architecture**: a platform-free mesh engine (Rust) behind a thin radio link layer
  (Kotlin, the same role CoreBluetooth plays on iOS). `LinkEvent` up, `LinkCommand` down.

```
┌────────────────────────────────────────────────────────┐
│                  Android app (Kotlin)                  │
│   Jetpack Compose UI  ·  foreground service  ·  BLE    │
│        radio I/O (scan / advertise / GATT links)       │
└───────────────────────┬────────────────────────────────┘
                        │ JNI  (LinkEvent ↑ / LinkCommand ↓)
┌───────────────────────┴────────────────────────────────┐
│                 cypher-core (Rust)                     │
│  binary protocol · Noise XX/X · Ed25519 · fragments    │
│  relay/flood control · source routing · peer registry  │
│  gossip sync (GCS) · courier mail · prekeys · groups   │
│  board · file transfer · push-to-talk · verification   │
└────────────────────────────────────────────────────────┘
```

## Features (mesh stack)

- **Decentralized mesh** — automatic peer discovery, multi-hop relay (TTL 7), deterministic
  fanout subsetting, source routing over confirmed topology (v2 frames)
- **End-to-end encryption** — Noise `XX` interactive sessions (X25519 + ChaCha20-Poly1305 +
  SHA-256) with replay-protected transport framing, session rekey, quarantine/rollback
  anti-hijack handling
- **Store-and-forward** — sender outbox, courier envelopes (one-way Noise `X` seals with
  rotating recipient tags), one-time prekeys for forward secrecy
- **Group chat** — creator-managed symmetric keys, epoch rotation, signed rosters
- **Bulletin board** — signed geohash-free/mesh-local posts with expiry and tombstones
- **Gossip sync** — Golomb-coded-set reconciliation of public history after churn
- **File transfer & private media** — 1 MiB payloads, fragmentation (469-byte chunks),
  durable receipts, capability-gated encrypted media
- **Push-to-talk voice** — burst-packetized public voice frames
- **IRC-style commands** — `/msg`, `/who`, `/slap`, `/group`, `/ping`, `/trace`, …
- **Verification** — QR codes, in-band challenge/response, transitive vouching
- **Emergency wipe** — panic reset that rotates the device identity

## Repository layout

| Path | Purpose |
|---|---|
| `crates/cypher-core` | Platform-free mesh engine + protocol + crypto (Rust) |
| `crates/cypher-android` | JNI bindings (`libcypher.so`) bridging engine ⇄ Kotlin radio/UI |
| `android/` | Android app: Compose UI, foreground service, BLE link layer |
| `docs/` | Architecture notes, porting specification, worklog |
| `WORKLOG.md` | Development log |

## Building

```bash
# Rust core (host: tests)
cargo test --workspace

# Android (debug APK)
cd android && ./gradlew :app:assembleDebug
```

## Attribution

Cypher is a port of [bitchat](https://github.com/permissionlesstech/bitchat) by
permissionlesstech, which is released into the public domain (The Unlicense). Cypher is
likewise public domain. The mesh protocol design, constants, and security architecture
originate from the bitchat project.

## License

[The Unlicense](LICENSE) — public domain.
