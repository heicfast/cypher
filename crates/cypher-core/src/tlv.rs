//! TLV encoding primitives.
//!
//! bitchat uses two TLV dialects:
//! * **short**: `[type u8][len u8][value]` (announce, private message, vouch,
//!   authenticated peer state)
//! * **long**: `[type u8][len u16 BE][value]` (courier, prekey bundle, file
//!   packet, board, request-sync, group state, nostr carrier)
//!
//! Both are tolerant decoders: unknown record types are skipped using the
//! length field; required records are validated by the caller.

/// Reader over an arbitrary-length TLV stream. `L` is the length-field width.
#[derive(Debug)]
pub struct TlvReader<'a> {
    data: &'a [u8],
    pos: usize,
    len_width: usize,
}

impl<'a> TlvReader<'a> {
    /// Reader for `[type u8][len u8][value]` records.
    pub fn short(data: &'a [u8]) -> Self {
        TlvReader {
            data,
            pos: 0,
            len_width: 1,
        }
    }

    /// Reader for `[type u8][len u16 BE][value]` records.
    pub fn long(data: &'a [u8]) -> Self {
        TlvReader {
            data,
            pos: 0,
            len_width: 2,
        }
    }

    pub fn remaining(&self) -> usize {
        self.data.len().saturating_sub(self.pos)
    }

    /// Next record, if any. Returns `(type, value)`.
    pub fn next_record(&mut self) -> Option<(u8, &'a [u8])> {
        if self.pos >= self.data.len() {
            return None;
        }
        let tag = self.data[self.pos];
        let len_start = self.pos + 1;
        let len_end = len_start + self.len_width;
        if len_end > self.data.len() {
            return None; // truncated length field
        }
        let len = match self.len_width {
            1 => self.data[len_start] as usize,
            _ => u16::from_be_bytes([self.data[len_start], self.data[len_start + 1]]) as usize,
        };
        let val_start = len_end;
        let val_end = val_start.checked_add(len)?;
        if val_end > self.data.len() {
            return None; // truncated value
        }
        let value = &self.data[val_start..val_end];
        self.pos = val_end;
        Some((tag, value))
    }
}

/// Append a short-form record (`u8` length).
pub fn write_short(out: &mut Vec<u8>, tag: u8, value: &[u8]) {
    debug_assert!(value.len() <= 255, "short TLV value overflow");
    out.push(tag);
    out.push(value.len().min(255) as u8);
    out.extend_from_slice(value);
}

/// Append a long-form record (`u16` BE length).
pub fn write_long(out: &mut Vec<u8>, tag: u8, value: &[u8]) {
    debug_assert!(value.len() <= 65535, "long TLV value overflow");
    out.push(tag);
    out.extend_from_slice(&(value.len().min(65535) as u16).to_be_bytes());
    out.extend_from_slice(value);
}

/// Collect all records with the given tag into a Vec (tolerant decode).
pub fn collect_all(data: &[u8], len_width: usize, wanted: u8) -> Vec<Vec<u8>> {
    let mut r = if len_width == 1 {
        TlvReader::short(data)
    } else {
        TlvReader::long(data)
    };
    let mut out = Vec::new();
    while let Some((tag, value)) = r.next_record() {
        if tag == wanted {
            out.push(value.to_vec());
        }
    }
    out
}

/// First record with the given tag.
pub fn find(data: &[u8], len_width: usize, wanted: u8) -> Option<Vec<u8>> {
    collect_all(data, len_width, wanted).into_iter().next()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn short_round_trip() {
        let mut buf = Vec::new();
        write_short(&mut buf, 0x01, b"nick");
        write_short(&mut buf, 0x02, &[0xAA; 32]);
        write_short(&mut buf, 0xFF, b"unknown");
        let mut r = TlvReader::short(&buf);
        assert_eq!(r.next_record(), Some((0x01, b"nick".as_slice())));
        let (t, v) = r.next_record().unwrap();
        assert_eq!((t, v.len()), (0x02, 32));
        assert_eq!(r.next_record().unwrap().0, 0xFF);
        assert_eq!(r.next_record(), None);
    }

    #[test]
    fn long_round_trip() {
        let mut buf = Vec::new();
        write_long(&mut buf, 0x03, &[0u8; 300]);
        let mut r = TlvReader::long(&buf);
        let (t, v) = r.next_record().unwrap();
        assert_eq!(t, 0x03);
        assert_eq!(v.len(), 300);
        assert_eq!(r.next_record(), None);
    }

    #[test]
    fn truncation_rejected() {
        let buf = [0x01u8, 0x05, 0x01, 0x02]; // claims len 5, has 2
        let mut r = TlvReader::short(&buf);
        assert_eq!(r.next_record(), None);
    }

    #[test]
    fn skip_unknown() {
        let mut buf = Vec::new();
        write_short(&mut buf, 0x7F, b"junkjunk");
        write_short(&mut buf, 0x02, b"key");
        assert_eq!(find(&buf, 1, 0x02), Some(b"key".to_vec()));
        assert_eq!(find(&buf, 1, 0x99), None);
    }
}
