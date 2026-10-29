//! Gossip sync: request packets, Golomb-coded-set filters, and sync type flags.

use crate::consts;
use crate::tlv::{write_long, TlvReader};
use crate::util::{be, sha256};

// ---------------------------------------------------------------------------
// Sync type flags
// ---------------------------------------------------------------------------

/// Bitfield of syncable packet types (little-endian, minimal encoding).
/// Bit i ↔ type: 0 announce, 1 message, 2 leave, 3 noiseHandshake,
/// 4 noiseEncrypted, 5 fragment, 6 requestSync, 7 fileTransfer, 8 boardPost,
/// 9 prekeyBundle, 10 groupMessage. Private traffic (courier, ping/pong,
/// nostrCarrier, voiceFrame, announceV2) never syncs.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub struct SyncTypeFlags(pub u64);

impl SyncTypeFlags {
    pub const ANNOUNCE: u64 = 1 << 0;
    pub const MESSAGE: u64 = 1 << 1;
    pub const LEAVE: u64 = 1 << 2;
    pub const NOISE_HANDSHAKE: u64 = 1 << 3;
    pub const NOISE_ENCRYPTED: u64 = 1 << 4;
    pub const FRAGMENT: u64 = 1 << 5;
    pub const REQUEST_SYNC: u64 = 1 << 6;
    pub const FILE_TRANSFER: u64 = 1 << 7;
    pub const BOARD_POST: u64 = 1 << 8;
    pub const PREKEY_BUNDLE: u64 = 1 << 9;
    pub const GROUP_MESSAGE: u64 = 1 << 10;

    const KNOWN_MASK: u64 = 0x7FF;

    pub fn insert(&mut self, bit: u64) {
        self.0 |= bit;
    }

    pub fn contains(self, bit: u64) -> bool {
        self.0 & bit != 0
    }

    /// All syncable types.
    pub fn all() -> Self {
        SyncTypeFlags(Self::KNOWN_MASK)
    }

    /// Minimal LE encoding, trailing zero bytes trimmed (≥1 byte when set).
    pub fn encode(self) -> Vec<u8> {
        let masked = self.0 & Self::KNOWN_MASK;
        if masked == 0 {
            return vec![0];
        }
        let mut bytes = masked.to_le_bytes().to_vec();
        while bytes.len() > 1 && *bytes.last().unwrap() == 0 {
            bytes.pop();
        }
        bytes
    }

    pub fn decode(bytes: &[u8]) -> Self {
        if bytes.is_empty() || bytes.len() > 8 {
            return SyncTypeFlags(0);
        }
        let mut raw = [0u8; 8];
        let n = bytes.len().min(8);
        raw[..n].copy_from_slice(&bytes[..n]);
        SyncTypeFlags(u64::from_le_bytes(raw) & Self::KNOWN_MASK)
    }
}

// ---------------------------------------------------------------------------
// Packet IDs (GCS inputs)
// ---------------------------------------------------------------------------

pub struct PacketIdUtil;

impl PacketIdUtil {
    /// 16-byte packet ID: `SHA-256(type ‖ senderID ‖ timestamp u64 BE ‖
    /// payload)[0..16]`.
    pub fn packet_id(packet_type: u8, sender_id: &[u8; 8], timestamp_ms: u64, payload: &[u8]) -> [u8; 16] {
        use sha2::Digest;
        let mut h = sha2::Sha256::new();
        h.update([packet_type]);
        h.update(sender_id);
        h.update(timestamp_ms.to_be_bytes());
        h.update(payload);
        let full: [u8; 32] = h.finalize().into();
        let mut id = [0u8; 16];
        id.copy_from_slice(&full[..16]);
        id
    }
}

// ---------------------------------------------------------------------------
// Golomb-coded sets
// ---------------------------------------------------------------------------

/// Golomb-Rice coded set filter over 16-byte packet IDs (FPR ≈ 1%).
pub struct GcsFilter;

impl GcsFilter {
    /// Derive P for a target false-positive rate: `P = ceil(log2(1/fpr))`.
    pub fn derive_p(target_fpr: f64) -> u8 {
        let p = (-target_fpr.log2()).ceil();
        p.clamp(1.0, consts::SYNC_GCS_MAX_P as f64) as u8
    }

    fn h64(id: &[u8; 16]) -> u64 {
        let digest = sha256(id);
        let mut eight = [0u8; 8];
        eight.copy_from_slice(&digest[..8]);
        u64::from_be_bytes(eight) & 0x7FFF_FFFF_FFFF_FFFF
    }

    /// Bucket for an ID: `h64 % M`, remapped `0 → 1` (avoids the zero bucket
    /// being unusable when `M` is even).
    pub fn bucket(id: &[u8; 16], m: u32) -> u64 {
        let value = Self::h64(id) % m as u64;
        if value == 0 {
            1
        } else {
            value
        }
    }

    /// Encode `ids` (any order) with Golomb-Rice parameter `p`.
    /// Returns `(encoded_bitstream, m)` with `M = min(N·2^P, u32::MAX)`.
    pub fn encode_ids(ids: &[[u8; 16]], p: u8) -> (Vec<u8>, u32) {
        let n = ids.len().max(1) as u64;
        let m = (n << p.min(32)).min(u32::MAX as u64) as u32;
        Self::encode_ids_exact(ids, p, m)
    }

    /// Decode a bitstream back to the sorted bucket values. Truncated input
    /// returns only the completely decoded values.
    pub fn decode_ids(data: &[u8], p: u8, m: u32) -> Vec<u64> {
        let mut out = Vec::new();
        let mut r = BitReader { data, pos: 0 };
        let mut prev: u64 = 0;
        loop {
            let mut q = 0u64;
            loop {
                match r.read_bit() {
                    Some(1) => q += 1,
                    Some(0) => break,
                    Some(_) => unreachable!(),
                    None => return out,
                }
            }
            let mut rem = 0u64;
            for _ in 0..p {
                match r.read_bit() {
                    Some(b) => rem = (rem << 1) | b as u64,
                    None => return out,
                }
            }
            let delta = ((q << p) | rem) + 1;
            let value = prev + delta;
            if value >= m as u64 || value == 0 {
                return out;
            }
            out.push(value);
            prev = value;
        }
    }

    /// Build a bounded filter: newest-first input, tail-trimmed to the byte
    /// budget (binary search on the included count). Returns
    /// `(encoded, m, included_count)`.
    pub fn build_filter(ids_newest_first: &[[u8; 16]], p: u8, max_bytes: usize) -> (Vec<u8>, u32, usize) {
        let total = ids_newest_first.len();
        if total == 0 {
            return (Vec::new(), 2, 0);
        }
        let m = (((total as u64) << p.min(32)) as u64).min(u32::MAX as u64) as u32;
        let fits = |count: usize| -> bool {
            if count == 0 {
                return true;
            }
            let (encoded, _) = Self::encode_ids_exact(&ids_newest_first[..count], p, m);
            encoded.len() <= max_bytes
        };
        // Binary search for the largest fitting count.
        let (mut lo, mut hi) = (0usize, total);
        while lo < hi {
            let mid = (lo + hi + 1) / 2;
            if fits(mid) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        if lo == 0 {
            return (Vec::new(), m, 0);
        }
        let (encoded, m_used) = Self::encode_ids_exact(&ids_newest_first[..lo], p, m);
        (encoded, m_used, lo)
    }

    fn encode_ids_exact(ids: &[[u8; 16]], p: u8, m: u32) -> (Vec<u8>, u32) {
        let mut buckets: Vec<u64> = ids.iter().map(|id| Self::bucket(id, m)).collect();
        buckets.sort_unstable();
        buckets.dedup();
        let mut bits = BitWriter::default();
        let mut prev = 0u64;
        for &b in &buckets {
            let v = b - prev - 1;
            prev = b;
            let q = v >> p;
            let r = v & ((1u64 << p) - 1);
            for _ in 0..q {
                bits.write_bit(1);
            }
            bits.write_bit(0);
            for i in (0..p).rev() {
                bits.write_bit(((r >> i) & 1) as u8);
            }
        }
        (bits.finish(), m)
    }

    /// Might the filter contain `id`? (FPR ≈ 1%.)
    pub fn might_contain(data: &[u8], p: u8, m: u32, id: &[u8; 16]) -> bool {
        let target = Self::bucket(id, m);
        let values = Self::decode_ids(data, p, m);
        values.binary_search(&target).is_ok()
    }
}

// Bit I/O helpers (MSB-first bitstream).

#[derive(Default)]
struct BitWriter {
    out: Vec<u8>,
    cur: u8,
    n: u8,
}

impl BitWriter {
    fn write_bit(&mut self, bit: u8) {
        self.cur = (self.cur << 1) | (bit & 1);
        self.n += 1;
        if self.n == 8 {
            self.out.push(self.cur);
            self.cur = 0;
            self.n = 0;
        }
    }

    fn finish(mut self) -> Vec<u8> {
        if self.n > 0 {
            self.cur <<= 8 - self.n;
            self.out.push(self.cur);
        }
        self.out
    }
}

struct BitReader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> BitReader<'a> {
    fn read_bit(&mut self) -> Option<u8> {
        let byte_index = self.pos / 8;
        if byte_index >= self.data.len() {
            return None;
        }
        let bit = 7 - (self.pos % 8);
        let b = (self.data[byte_index] >> bit) & 1;
        self.pos += 1;
        Some(b)
    }
}

// ---------------------------------------------------------------------------
// RequestSyncPacket
// ---------------------------------------------------------------------------

/// `requestSync` (0x21) payload — TLV with 2-byte BE lengths:
/// `0x01 P(1)`, `0x02 M(4)`, `0x03 data(≤1024)`, `0x04 types(LE minimal)`,
/// `0x05 sinceTimestamp(8)`, `0x06 fragmentIdFilter (comma-separated 16-hex
/// stream IDs, ≤60)`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RequestSyncPacket {
    pub p: u8,
    pub m: u32,
    pub data: Vec<u8>,
    pub types: SyncTypeFlags,
    pub since_timestamp: u64,
    pub fragment_id_filter: Option<Vec<String>>,
}

impl RequestSyncPacket {
    pub const MAX_P: u8 = consts::SYNC_GCS_MAX_P;
    pub const MAX_ENCODED_BYTES: usize = consts::REQUEST_SYNC_MAX_ENCODED_BYTES;
    pub const MAX_FRAGMENT_FILTER: usize = consts::REQUEST_SYNC_MAX_FRAGMENT_FILTER;

    pub fn encode(&self) -> Option<Vec<u8>> {
        if self.p == 0 || self.p > Self::MAX_P {
            return None;
        }
        if self.m == 0 {
            return None;
        }
        // The GCS bitstream TLV value is bounded (400 B by construction, 1024
        // B hard cap on read).
        if self.data.len() > Self::MAX_ENCODED_BYTES {
            return None;
        }
        let mut out = Vec::new();
        write_long(&mut out, 0x01, &[self.p]);
        write_long(&mut out, 0x02, &self.m.to_be_bytes());
        write_long(&mut out, 0x03, &self.data);
        write_long(&mut out, 0x04, &self.types.encode());
        write_long(&mut out, 0x05, &self.since_timestamp.to_be_bytes());
        if let Some(filter) = &self.fragment_id_filter {
            let joined = filter
                .iter()
                .take(Self::MAX_FRAGMENT_FILTER)
                .cloned()
                .collect::<Vec<_>>()
                .join(",");
            write_long(&mut out, 0x06, joined.as_bytes());
        }
        Some(out)
    }

    pub fn decode(data: &[u8]) -> Option<Self> {
        let mut p: Option<u8> = None;
        let mut m: Option<u32> = None;
        let mut gcs_data: Option<Vec<u8>> = None;
        let mut types = SyncTypeFlags::default();
        let mut since_timestamp: u64 = 0;
        let mut fragment_id_filter: Option<Vec<String>> = None;

        let mut reader = TlvReader::long(data);
        while let Some((tag, value)) = reader.next_record() {
            match tag {
                0x01 => {
                    if value.len() != 1 || value[0] == 0 || value[0] > Self::MAX_P {
                        return None;
                    }
                    p = Some(value[0]);
                }
                0x02 => {
                    if value.len() != 4 {
                        return None;
                    }
                    m = Some(u32::from_be_bytes(value.try_into().ok()?));
                }
                0x03 => {
                    if value.len() > Self::MAX_ENCODED_BYTES {
                        return None;
                    }
                    gcs_data = Some(value.to_vec());
                }
                0x04 => types = SyncTypeFlags::decode(value),
                0x05 => {
                    if value.len() == 8 {
                        since_timestamp = u64::from_be_bytes(value.try_into().ok()?);
                    }
                }
                0x06 => {
                    let joined = String::from_utf8(value.to_vec()).ok()?;
                    let ids: Vec<String> = joined
                        .split(',')
                        .filter(|s| s.len() == 16 && s.bytes().all(|b| b.is_ascii_hexdigit()))
                        .map(|s| s.to_lowercase())
                        .take(Self::MAX_FRAGMENT_FILTER)
                        .collect();
                    fragment_id_filter = Some(ids);
                }
                _ => {} // ignore unknown
            }
        }

        let m = m?;
        // m must be a usable hash range: 0 and 1 are rejected (with m=1 every
        // bucket remaps to 1, making the filter useless).
        if m <= 1 {
            return None;
        }
        Some(RequestSyncPacket {
            p: p?,
            m,
            data: gcs_data.unwrap_or_default(),
            types,
            since_timestamp,
            fragment_id_filter,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ids(n: usize) -> Vec<[u8; 16]> {
        (0..n).map(|i| sha256(&(i as u64).to_be_bytes())[..16].try_into().unwrap()).collect()
    }

    fn ids_offset(start: u64, n: usize) -> Vec<[u8; 16]> {
        (start..start + n as u64)
            .map(|i| sha256(&i.to_be_bytes())[..16].try_into().unwrap())
            .collect()
    }

    #[test]
    fn derive_p_for_one_percent() {
        assert_eq!(GcsFilter::derive_p(0.01), 7);
    }

    #[test]
    fn bucket_avoids_zero() {
        // With m = 2 any h64 % 2 == 0 must remap to 1.
        for id in ids(32) {
            let b = GcsFilter::bucket(&id, 2);
            assert!(b == 1 || b == 2);
        }
    }

    #[test]
    fn round_trip_membership() {
        let set = ids(100);
        let (encoded, m) = GcsFilter::encode_ids(&set, 7);
        for id in &set {
            assert!(GcsFilter::might_contain(&encoded, 7, m, id), "member must pass");
        }
        // Non-members (disjoint range) pass with ~1% probability.
        let non = ids_offset(100_000, 200);
        let false_positives = non.iter().filter(|id| GcsFilter::might_contain(&encoded, 7, m, id)).count();
        assert!(false_positives <= 10, "FPR out of band: {false_positives}/200");
    }

    #[test]
    fn decode_truncated_returns_complete_values() {
        let set = ids(50);
        let (mut encoded, m) = GcsFilter::encode_ids(&set, 7);
        // Chop bytes; decode must never panic or invent values.
        encoded.truncate(encoded.len() / 2);
        let values = GcsFilter::decode_ids(&encoded, 7, m);
        assert!(values.len() <= 50);
    }

    #[test]
    fn budget_trims_oldest() {
        // newest-first input; heavy tail must be trimmed to fit 400 bytes.
        let mut set = ids(3000);
        set.reverse();
        let (encoded, _m, included) = GcsFilter::build_filter(&set, 7, consts::SYNC_GCS_MAX_BYTES);
        assert!(encoded.len() <= consts::SYNC_GCS_MAX_BYTES);
        assert!(included < 3000);
        assert!(included > 0);
        // Survivors are the newest prefix.
        for id in set[..included.min(50)].iter() {
            assert!(GcsFilter::might_contain(&encoded, 7, _m, id));
        }
    }

    #[test]
    fn decode_rejects_bad_params() {
        let set = ids(10);
        let (encoded, m) = GcsFilter::encode_ids(&set, 7);
        assert!(RequestSyncPacket::decode(&make_request(0, m, &encoded)).is_none());
        assert!(RequestSyncPacket::decode(&make_request(200, m, &encoded)).is_none());
        assert!(RequestSyncPacket::decode(&make_request(7, 0, &encoded)).is_none());
        assert!(RequestSyncPacket::decode(&make_request(7, 1, &encoded)).is_none());
        let good = make_request(7, m, &encoded);
        assert!(RequestSyncPacket::decode(&good).is_some());
    }

    fn make_request(p: u8, m: u32, data: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        write_long(&mut out, 0x01, &[p]);
        write_long(&mut out, 0x02, &m.to_be_bytes());
        write_long(&mut out, 0x03, data);
        out
    }

    #[test]
    fn fragment_filter_round_trip_and_caps() {
        let mut pkt = RequestSyncPacket {
            p: 7,
            m: 1024,
            data: vec![],
            types: SyncTypeFlags::all(),
            since_timestamp: 1_750_000_000_000,
            fragment_id_filter: None,
        };
        let filter: Vec<String> = (0..80).map(|i| format!("{i:016x}")).collect();
        pkt.fragment_id_filter = Some(filter.clone());
        let encoded = pkt.encode().unwrap();
        let decoded = RequestSyncPacket::decode(&encoded).unwrap();
        let got = decoded.fragment_id_filter.unwrap();
        assert_eq!(got.len(), RequestSyncPacket::MAX_FRAGMENT_FILTER);
        assert_eq!(got[0], "0000000000000000");
        // Malformed tokens are dropped.
        let mut raw = encoded.clone();
        let mut bad = Vec::new();
        write_long(&mut bad, 0x06, b"zz,0011223344556677,short");
        raw.extend_from_slice(&bad);
        let decoded2 = RequestSyncPacket::decode(&raw).unwrap();
        assert_eq!(
            decoded2.fragment_id_filter.unwrap(),
            vec!["0011223344556677".to_string()]
        );
    }

    #[test]
    fn sync_flags_round_trip_and_mask() {
        let flags = SyncTypeFlags::all();
        assert_eq!(SyncTypeFlags::decode(&flags.encode()), flags);
        // Phantom bits dropped.
        let phantom = SyncTypeFlags(0xFFFF_FFFF_FFFF_FFFF);
        assert_eq!(SyncTypeFlags::decode(&phantom.encode()), SyncTypeFlags::all());
        assert!(flags.contains(SyncTypeFlags::GROUP_MESSAGE));
        assert!(flags.contains(SyncTypeFlags::BOARD_POST));
        assert!(!flags.contains(1 << 11));
    }

    #[test]
    fn packet_id_is_stable() {
        let a = PacketIdUtil::packet_id(0x02, &[1; 8], 1000, b"payload");
        let b = PacketIdUtil::packet_id(0x02, &[1; 8], 1000, b"payload");
        let c = PacketIdUtil::packet_id(0x02, &[1; 8], 1001, b"payload");
        assert_eq!(a, b);
        assert_ne!(a, c);
        assert_eq!(a.len(), 16);
    }
}
