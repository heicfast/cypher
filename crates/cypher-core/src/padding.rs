//! Frame padding for `noiseHandshake` / `noiseEncrypted` frames.
//!
//! PKCS#7-style padding toward 256/512/1024/2048-byte buckets. The pad value
//! accounts for the 16-byte AEAD tag; frames needing more than 255 pad bytes
//! ship unpadded. Mirrors bitchat's `MessagePadding` exactly (the padding is
//! part of the signed byte stream — see `docs/courier-test-vectors.json`).

pub const BLOCK_SIZES: [usize; 4] = [256, 512, 1024, 2048];

/// Smallest block that fits `size + 16` (AEAD tag), if any.
pub fn optimal_block_size(size: usize) -> Option<usize> {
    BLOCK_SIZES.iter().copied().find(|&b| size + 16 <= b)
}

/// Pad `data` toward the nearest bucket. Returns the input unchanged when it
/// already fits no bucket (>= 2048-16) or the required pad exceeds 255 bytes.
pub fn pad(data: &[u8]) -> Vec<u8> {
    match optimal_block_size(data.len()) {
        Some(target) => {
            let shortfall = target - data.len();
            if shortfall == 0 || shortfall > 255 {
                return data.to_vec();
            }
            let mut out = Vec::with_capacity(target);
            out.extend_from_slice(data);
            out.extend(std::vec::from_elem(shortfall as u8, shortfall));
            out
        }
        None => data.to_vec(),
    }
}

/// Strip padding: every trailing byte must equal the last byte (the pad
/// length), otherwise the input is returned unchanged (tolerant, like bitchat).
pub fn unpad(data: &[u8]) -> Vec<u8> {
    if data.is_empty() {
        return data.to_vec();
    }
    let last = data[data.len() - 1];
    if last == 0 || last as usize > data.len() {
        return data.to_vec();
    }
    let pad_len = last as usize;
    if data[data.len() - pad_len..].iter().all(|&b| b == last) {
        data[..data.len() - pad_len].to_vec()
    } else {
        data.to_vec()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pads_to_256() {
        // 104 bytes -> 256 target, shortfall 152 = 0x98 (golden vector shape)
        let data = vec![0xAB; 104];
        let padded = pad(&data);
        assert_eq!(padded.len(), 256);
        assert_eq!(padded[104], 0x98);
        assert!(padded[104..].iter().all(|&b| b == 0x98));
        assert_eq!(unpad(&padded), data);
    }

    #[test]
    fn near_boundary_unpadded() {
        // 255 bytes: 255+16 = 271 > 256 -> target 512, shortfall 257 > 255 → unpadded
        let data = vec![0x01; 255];
        assert_eq!(pad(&data).len(), 255);
        // 240 bytes: 240+16 = 256 -> exact fit, shortfall 16
        let data2 = vec![0x01; 240];
        assert_eq!(pad(&data2).len(), 256);
    }

    #[test]
    fn oversized_unpadded() {
        // Non-uniform tail: no padding is detected, input returned unchanged.
        let data: Vec<u8> = (0..5000u32).map(|i| (i % 251) as u8).collect();
        assert_eq!(pad(&data).len(), 5000);
        assert_eq!(unpad(&data).len(), 5000);
    }

    #[test]
    fn unpad_tolerant() {
        // Invalid tail (not a uniform run) returns input unchanged.
        let data = [0x01u8, 0x02, 0x03, 0x99, 0x98];
        assert_eq!(unpad(&data), data.to_vec());
    }
}
