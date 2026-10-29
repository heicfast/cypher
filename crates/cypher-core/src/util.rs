//! Low-level utilities: hex, hashing, fingerprints, time.
//!
//! Byte-compatible with bitchat's `Data+Hex` / `Data+SHA256` helpers:
//! hex output is **lowercase**, fingerprints are `hex(SHA256(data))`.

use sha2::{Digest, Sha256};

/// Lowercase hex encoding of a byte slice (matches `Data.hexEncodedString()`).
pub fn hex_encode(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for &b in bytes {
        out.push(HEX[(b >> 4) as usize] as char);
        out.push(HEX[(b & 0x0f) as usize] as char);
    }
    out
}

/// Parse a hex string into bytes. Mirrors bitchat's tolerant parser:
/// an optional `0x` prefix is stripped; odd-length input is rejected.
pub fn hex_decode(s: &str) -> Option<Vec<u8>> {
    let s = s.strip_prefix("0x").unwrap_or(s);
    if s.len() % 2 != 0 {
        return None;
    }
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(s.len() / 2);
    for i in (0..bytes.len()).step_by(2) {
        let hi = hex_val(bytes[i])?;
        let lo = hex_val(bytes[i + 1])?;
        out.push((hi << 4) | lo);
    }
    Some(out)
}

fn hex_val(c: u8) -> Option<u8> {
    match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'a'..=b'f' => Some(c - b'a' + 10),
        b'A'..=b'F' => Some(c - b'A' + 10),
        _ => None,
    }
}

/// SHA-256 digest.
pub fn sha256(data: &[u8]) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update(data);
    h.finalize().into()
}

/// HMAC-SHA256.
pub fn hmac_sha256(key: &[u8], data: &[u8]) -> [u8; 32] {
    use hmac::{Hmac, Mac};
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("HMAC accepts any key length");
    mac.update(data);
    mac.finalize().into_bytes().into()
}

/// SHA-256 fingerprint: lowercase hex of the SHA-256 of `data` (64 chars).
/// This exact string is a device's persistent identity in bitchat.
pub fn sha256_fingerprint(data: &[u8]) -> String {
    hex_encode(&sha256(data))
}

/// Current wall clock in milliseconds since the Unix epoch (packet timestamps).
pub fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Current wall clock in seconds since the Unix epoch.
pub fn now_s() -> u64 {
    now_ms() / 1000
}

/// DJB2 hash over UTF-8 bytes (bitchat uses this for content dedup keys and
/// deterministic peer colors). `hash = ((hash << 5) + hash) + byte`, init 5381.
pub fn djb2(s: &str) -> u64 {
    let mut hash: u64 = 5381;
    for b in s.as_bytes() {
        hash = ((hash << 5).wrapping_add(hash)).wrapping_add(*b as u64);
    }
    hash
}

/// Format a DJB2 content hash the way bitchat does: `"h:%016llx"`.
pub fn content_hash_key(normalized: &str) -> String {
    format!("h:{:016x}", djb2(normalized))
}

/// Big-endian readers/writers over a byte buffer (BinaryProtocol is BE everywhere).
pub mod be {
    pub fn u16(buf: &[u8], off: usize) -> Option<u16> {
        buf.get(off..off + 2).map(|b| u16::from_be_bytes([b[0], b[1]]))
    }
    pub fn u32(buf: &[u8], off: usize) -> Option<u32> {
        buf.get(off..off + 4)
            .map(|b| u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
    }
    pub fn u64(buf: &[u8], off: usize) -> Option<u64> {
        buf.get(off..off + 8)
            .map(|b| u64::from_be_bytes([b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]]))
    }
    pub fn put_u16(out: &mut Vec<u8>, v: u16) {
        out.extend_from_slice(&v.to_be_bytes());
    }
    pub fn put_u32(out: &mut Vec<u8>, v: u32) {
        out.extend_from_slice(&v.to_be_bytes());
    }
    pub fn put_u64(out: &mut Vec<u8>, v: u64) {
        out.extend_from_slice(&v.to_be_bytes());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hex_round_trip() {
        let data = [0x00u8, 0x01, 0xab, 0xff];
        let s = hex_encode(&data);
        assert_eq!(s, "0001abff");
        assert_eq!(hex_decode(&s).unwrap(), data);
        assert_eq!(hex_decode("0x0001abff").unwrap(), data);
        assert!(hex_decode("abc").is_none());
        assert!(hex_decode("zz").is_none());
    }

    #[test]
    fn sha256_known_vector() {
        // SHA-256("abc")
        assert_eq!(
            hex_encode(&sha256(b"abc")),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn fingerprint_of_zeros() {
        // SHA-256 of 32 zero bytes
        assert_eq!(
            sha256_fingerprint(&[0u8; 32]),
            "66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925"
        );
    }

    #[test]
    fn djb2_known() {
        // djb2("hello") over u64 = 210714636441
        assert_eq!(djb2("hello"), 210714636441);
    }
}
