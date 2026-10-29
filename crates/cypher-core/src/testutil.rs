//! Test-only helpers.

/// Decode a hex literal (test vectors), joining continuation lines.
#[cfg(test)]
pub fn unhex(s: &str) -> Vec<u8> {
    let cleaned: String = s.chars().filter(|c| !c.is_whitespace()).collect();
    crate::util::hex_decode(&cleaned).expect("valid hex in test vector")
}
