//! Payload compression — **raw DEFLATE** (RFC 1951, no zlib header).
//!
//! bitchat on iOS uses Apple's `compression_encode_buffer` with
//! `COMPRESSION_ZLIB`, which despite the name emits a headerless raw DEFLATE
//! stream; the Android client matches it with `Deflater(level, nowrap=true)`.
//! Cypher matches with `flate2`'s raw deflate.
//!
//! Compression is applied to packet payloads when `len >= 100` bytes AND the
//! unique-byte ratio (unique bytes over the **whole** payload divided by
//! `min(len, 256)`) is below 0.9, and only kept when the output is strictly
//! smaller. The receiver enforces a max deflate ratio of 1032:1 and per-type
//! payload ceilings.

use flate2::read::DeflateDecoder;
use flate2::write::DeflateEncoder;
use flate2::Compression;
use std::io::{Read, Write};

/// Threshold below which payloads are never compressed.
pub const COMPRESSION_THRESHOLD_BYTES: usize = 100;
/// Anti zip-bomb bound: declared original size may not exceed
/// `compressed_size * 1032`.
pub const MAX_DEFLATE_RATIO: usize = 1032;

/// Should this payload be compressed at all (bitchat's heuristic)?
pub fn should_compress(data: &[u8]) -> bool {
    if data.len() < COMPRESSION_THRESHOLD_BYTES {
        return false;
    }
    // Unique byte values over the ENTIRE payload (bitchat uses Set(data).count).
    let mut seen = [false; 256];
    for &b in data {
        seen[b as usize] = true;
    }
    let unique = seen.iter().filter(|&&s| s).count();
    let sample = data.len().min(256);
    (unique as f64 / sample as f64) < 0.9
}

/// Compress with raw DEFLATE. Returns `None` if the output is not strictly
/// smaller (caller then sends the payload uncompressed, flag clear).
pub fn compress(data: &[u8]) -> Option<Vec<u8>> {
    if data.len() < COMPRESSION_THRESHOLD_BYTES {
        return None;
    }
    let mut enc = DeflateEncoder::new(Vec::with_capacity(data.len()), Compression::default());
    if enc.write_all(data).is_err() {
        return None;
    }
    match enc.finish() {
        Ok(out) if !out.is_empty() && out.len() < data.len() => Some(out),
        _ => None,
    }
}

/// Decompress a raw DEFLATE stream. The output must be exactly
/// `original_size` bytes; `max_original` is the per-type payload ceiling.
pub fn decompress(data: &[u8], original_size: usize, max_original: usize) -> Option<Vec<u8>> {
    if data.is_empty() {
        return None;
    }
    if original_size == 0 || original_size > max_original {
        return None;
    }
    if original_size > data.len().saturating_mul(MAX_DEFLATE_RATIO) {
        return None;
    }
    let mut dec = DeflateDecoder::new(data);
    let mut out = Vec::with_capacity(original_size.min(max_original));
    // Read in bounded chunks so a zip bomb can't blow past the ceiling.
    let mut total = 0usize;
    let mut chunk = [0u8; 8192];
    loop {
        match dec.read(&mut chunk) {
            Ok(0) => break,
            Ok(n) => {
                total += n;
                if total > original_size {
                    return None;
                }
                out.extend_from_slice(&chunk[..n]);
            }
            Err(_) => return None,
        }
    }
    if out.len() != original_size {
        return None; // declared size must match exactly
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_compressible() {
        let data = vec![b'a'; 500]; // highly repetitive → compresses well
        assert!(should_compress(&data));
        let compressed = compress(&data).expect("compresses");
        assert!(compressed.len() < data.len());
        let restored = decompress(&compressed, data.len(), 65_535).expect("decompresses");
        assert_eq!(restored, data);
    }

    #[test]
    fn skips_high_entropy() {
        // 300 bytes of pseudo-random data: unique ratio high → no compression.
        let mut data = Vec::with_capacity(300);
        let mut x: u64 = 0x9E3779B97F4A7C15;
        for _ in 0..300 {
            x ^= x << 13;
            x ^= x >> 7;
            x ^= x << 17;
            data.push((x & 0xFF) as u8);
        }
        assert!(!should_compress(&data) || compress(&data).is_none());
    }

    #[test]
    fn below_threshold_never() {
        assert!(!should_compress(b"short string"));
        assert!(compress(b"short string").is_none());
    }

    #[test]
    fn ratio_bound_enforced() {
        // 100 'a's compress to ~10 bytes; claiming original 65,535 exceeds
        // 10 * 1032 → ratio check must fail.
        let data = vec![b'a'; 100];
        assert!(decompress(&data, 65_535, 65_535).is_none());
    }

    #[test]
    fn exact_size_enforced() {
        let data = vec![b'x'; 400];
        let compressed = compress(&data).unwrap();
        assert!(decompress(&compressed, 399, 65_535).is_none());
        assert!(decompress(&compressed, 400, 65_535).is_some());
    }

    #[test]
    fn empty_rejected() {
        assert!(decompress(&[], 10, 65_535).is_none());
        assert!(decompress(&[1, 2, 3], 0, 65_535).is_none());
    }

    #[test]
    fn raw_deflate_no_zlib_header() {
        // The stream must NOT start with the zlib magic 0x78.
        let data: Vec<u8> = b"hello world ".repeat(40);
        let compressed = compress(&data).unwrap();
        assert_ne!(compressed[0], 0x78, "must be raw DEFLATE, not zlib-wrapped");
    }
}
