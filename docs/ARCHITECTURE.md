# Cypher Architecture

Cypher is a Rust port of the bitchat Bluetooth LE mesh core for Android, with a
single transport: the Bluetooth mesh. This document maps the port onto the
original architecture and records the boundary contracts.

## Layering (mirrors bitchat's `BLE-ARCHITECTURE-V3`)

```
┌──────────────────────────────────────────────────────────────┐
│ Android app (Kotlin)                                         │
│   Compose UI (bitchat design system) · foreground service    │
│   BLE radio I/O: scanner · advertiser · GATT client/server   │
└──────────────────────────┬───────────────────────────────────┘
                           │ JNI — LinkEvent ↑ / LinkCommand ↓
┌──────────────────────────┴───────────────────────────────────┐
│ cypher-core (Rust) — the mesh engine                         │
│                                                              │
│  link.rs      the radio boundary (the CoreBluetooth role)    │
│  stream.rs    characteristic byte-stream → frames            │
│  engine.rs    sans-I/O state machine: ingress attribution,   │
│               dedup, dispatch, relay scheduling, announce    │
│  relay.rs     flood control (TTL clamps, degree jitter)      │
│  fanout.rs    deterministic log-subset broadcast fanout      │
│  fragmentation.rs  469-byte trains + reassembly buffer       │
│  peer_registry.rs  verified peers, TOFU pins, retention      │
│  topology.rs  confirmed-edge BFS source routing (v2 frames)  │
│  announce.rs  trust ladder + cadence                         │
│  noise/       Noise XX sessions + X seals                    │
│  protocol/    wire payload codecs (TLV families)             │
│  features/    courier mail, outbox, prekeys, gossip sync     │
└──────────────────────────────────────────────────────────────┘
```

The engine is I/O-free and clock-injected: hosts feed `LinkEvent`s and call
`tick()` at the returned deadline. Timed behaviour (relay jitter, announce
cadence, 5 s maintenance, fragment expiry) executes inside `tick`, which
makes the whole mesh deterministic under a manual clock — proven by the
simulated-mesh integration tests that wire two engines together through the
same seam the Android BLE layer uses.

## Wire compatibility

Cypher speaks the bitchat binary protocol byte-for-byte:

- Same GATT service (`F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C`) and
  characteristic (`A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D`); advertising
  carries the service UUID only (maximum privacy).
- Same frame format (v1 14-byte / v2 16-byte BE headers), same flag bits,
  same Ed25519 preimage construction (`ttl=0`, signature omitted, `isRSR`
  cleared, PKCS#7-padded — verified against the golden vectors).
- Same Noise suite `Noise_XX_25519_ChaChaPoly_SHA256` with the bitchat
  transport framing (explicit 4-byte BE nonce, 1024-entry replay window).
- Same DEFLATE payload compression (Apple's `COMPRESSION_ZLIB` emits raw
  DEFLATE; matched).
- Same constants: TTL 7, 469-byte fragments, 120 s timestamp skew, all
  timeouts and cadences (see `consts.rs` — the behavioural contract).

Decoded packets preserve the originator's payload bytes (`WirePayload`) so
re-encodes — including relay TTL decrements and signature preimages — are
byte-identical across clients, a deliberate cross-platform compatibility
mechanism proven in the Android implementation.

## Security model (ported 1:1)

- **Identity**: X25519 static key (Noise) + Ed25519 signing key. On-air peer
  ID = first 8 bytes of `SHA-256(noise public key)`. TOFU pinning of signing
  keys; announces carry a packet-level Ed25519 signature verified against the
  pinned key.
- **Interactive sessions**: Noise XX (mutual auth, identity hiding, KCI
  resistance), 24 h session ceiling, 10⁹ message budget, 90 % rekey
  heuristic, replay-protected transport with atomic state rollback.
- **Store-and-forward**: one-way Noise X seals — v1 to the recipient's static
  key, v2 to one-time prekeys (forward secrecy) with domain-separated
  prologues (`bitchat-courier-v1`, `bitchat-prekey-v1 ‖ u32_be(id)`).
  Recipients addressed by rotating HMAC tags (day ± 1 window).
- **Announce trust ladder**: identity derivation check, freshness (900 s),
  key-mismatch rejection, signature verification — unverified announces
  never enter the registry.
- **Low-order point blacklist** on all received Curve25519 keys, with
  constant-time comparisons.
- **Rate limits**: 30/min global + 10/min per-peer handshakes; 500/s global
  + 100/s per-peer messages; sync responses 8/30 s.

## Deltas from bitchat (v0.2 backlog)

Recorded in `WORKLOG.md`: the NoiseSessionManager quarantine/rollback state
machine, engine-side gossip scheduling, private-media admission gating,
courier deposit wiring into the engine, wear module.
