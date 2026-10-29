//! Notification-stream assembler: turns the characteristic's byte stream
//! back into individual packet frames.
//!
//! The characteristic is a byte stream — frames concatenated back-to-back,
//! optionally PKCS#7-padded. The assembler buffers chunks, discards leading
//! padding runs, parses frame lengths from headers, and emits complete
//! frames in order. Hard cap 8 MiB (stream reset); stall reset after 250 ms
//! without progress.

use crate::packet::{BinaryProtocol, V1_HEADER_SIZE, V2_HEADER_SIZE};
use crate::peer_id::PEER_ID_WIRE_BYTES;
use crate::types::flags;

pub const HARD_CAP_BYTES: usize = 8 * 1024 * 1024;
pub const STALL_RESET_MS: u64 = 250;

/// A frame extracted from the stream (raw frame bytes, padding removed by
/// the packet decoder).
#[derive(Clone, Debug, PartialEq)]
pub struct AssembledFrame(pub Vec<u8>);

#[derive(Default, Clone, Debug)]
pub struct StreamAssembler {
    buffer: Vec<u8>,
    /// Millisecond timestamp when the current partial frame started, for
    /// stall detection (driven by the engine's clock).
    pending_frame_started_at: Option<u64>,
}

impl StreamAssembler {
    pub fn new() -> Self {
        Self::default()
    }

    /// Feed a chunk; returns completed frames and whether the stream was
    /// reset (overflow). `now_ms` drives the stall reset.
    pub fn ingest(&mut self, chunk: &[u8], now_ms: u64) -> (Vec<AssembledFrame>, bool) {
        let mut frames = Vec::new();
        let mut reset = false;

        if self.pending_frame_started_at.is_none() && !self.buffer.is_empty() {
            self.pending_frame_started_at = Some(now_ms);
        }
        self.buffer.extend_from_slice(chunk);

        loop {
            match self.try_extract() {
                Ok(Some(frame)) => {
                    frames.push(AssembledFrame(frame));
                    self.pending_frame_started_at = if self.buffer.is_empty() {
                        None
                    } else {
                        Some(now_ms)
                    };
                }
                Ok(None) => break,
                Err(()) => {
                    // Overflow or garbage: reset the stream.
                    self.buffer.clear();
                    self.pending_frame_started_at = None;
                    reset = true;
                    break;
                }
            }
        }

        if self.buffer.len() > HARD_CAP_BYTES {
            self.buffer.clear();
            self.pending_frame_started_at = None;
            reset = true;
        }

        (frames, reset)
    }

    /// Force-clear (link teardown).
    pub fn reset(&mut self) {
        self.buffer.clear();
        self.pending_frame_started_at = None;
    }

    pub fn buffered(&self) -> usize {
        self.buffer.len()
    }

    /// Try to extract one complete frame. `Err(())` means the stream is
    /// unrecoverable (garbage header beyond the frame cap). Iterative —
    /// resynchronization drops one byte at a time without recursion.
    fn try_extract(&mut self) -> Result<Option<Vec<u8>>, ()> {
        loop {
            // Skip leading padding runs / garbage until a plausible header.
            let mut skip = 0usize;
            while skip < self.buffer.len() {
                let version = self.buffer[skip];
                if version == 1 || version == 2 {
                    break;
                }
                skip += 1;
            }
            if skip > 0 {
                self.buffer.drain(..skip);
            }
            if self.buffer.is_empty() {
                return Ok(None);
            }

            let version = self.buffer[0];
            let header_size = if version == 2 { V2_HEADER_SIZE } else { V1_HEADER_SIZE };
            if self.buffer.len() < header_size + PEER_ID_WIRE_BYTES {
                return Ok(None); // need more bytes
            }

            let f = self.buffer[11];
            let has_recipient = f & flags::HAS_RECIPIENT != 0;
            let has_route = version >= 2 && f & flags::HAS_ROUTE != 0;
            let _is_compressed = f & flags::IS_COMPRESSED != 0;
            let has_signature = f & flags::HAS_SIGNATURE != 0;

            let payload_length = if version == 2 {
                u32::from_be_bytes(self.buffer[12..16].try_into().unwrap()) as usize
            } else {
                u16::from_be_bytes(self.buffer[12..14].try_into().unwrap()) as usize
            };
            if payload_length > crate::consts::FILE_MAX_FRAMED_BYTES {
                return Err(()); // garbage header
            }

            let mut frame_length = header_size
                + PEER_ID_WIRE_BYTES
                + if has_recipient { PEER_ID_WIRE_BYTES } else { 0 };
            if has_route {
                if self.buffer.len() < frame_length + 1 {
                    return Ok(None);
                }
                let hop_count = self.buffer[frame_length] as usize;
                frame_length += 1 + hop_count * PEER_ID_WIRE_BYTES;
            }
            // payloadLength covers the payload region INCLUDING the
            // compressed-original-size preamble when the compressed flag is
            // set (route bytes are excluded).
            frame_length += payload_length;
            if has_signature {
                frame_length += 64;
            }

            if self.buffer.len() < frame_length {
                return Ok(None); // need more bytes
            }
            let frame: Vec<u8> = self.buffer.drain(..frame_length).collect();
            // Sanity: the frame must decode; if not, its first byte was
            // garbage — drop it and resynchronize.
            if BinaryProtocol::decode(&frame).is_none() {
                let (_, rest) = frame.split_first().unwrap();
                let keep = self.buffer.clone();
                let mut rebuilt = rest.to_vec();
                rebuilt.extend_from_slice(&keep);
                self.buffer = rebuilt;
                continue;
            }
            return Ok(Some(frame));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(payload_len: usize) -> Vec<u8> {
        // Build a minimal valid v1 frame via the packet codec.
        let pkt = crate::packet::BitchatPacket::new(
            0x02,
            [1, 2, 3, 4, 5, 6, 7, 8],
            None,
            1000,
            vec![b'x'; payload_len],
            7,
        );
        pkt.to_binary_data(false).unwrap()
    }

    #[test]
    fn concatenated_frames_in_order() {
        let mut asm = StreamAssembler::new();
        let f1 = frame(10);
        let f2 = frame(20);
        let mut stream = f1.clone();
        stream.extend_from_slice(&f2);
        let (frames, reset) = asm.ingest(&stream, 0);
        assert!(!reset);
        assert_eq!(frames.len(), 2);
        assert_eq!(frames[0].0, f1);
        assert_eq!(frames[1].0, f2);
    }

    #[test]
    fn chunked_delivery() {
        let mut asm = StreamAssembler::new();
        let f = frame(50);
        let mut out = Vec::new();
        let mut got_reset = false;
        for chunk in f.chunks(7) {
            let (frames, reset) = asm.ingest(chunk, 0);
            got_reset |= reset;
            out.extend(frames);
        }
        assert!(!got_reset);
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].0, f);
    }

    #[test]
    fn padded_frames_extracted() {
        let mut asm = StreamAssembler::new();
        let mut pkt = crate::packet::BitchatPacket::new(
            0x11,
            [1; 8],
            None,
            42,
            vec![0x33; 64],
            7,
        );
        // Wire with padding (noise frames are padded).
        let padded = pkt.to_binary_data(true).unwrap();
        let mut stream = padded.clone();
        pkt.ttl = 6;
        stream.extend_from_slice(&pkt.to_binary_data(true).unwrap());
        let (frames, _) = asm.ingest(&stream, 0);
        assert_eq!(frames.len(), 2);
    }

    #[test]
    fn leading_garbage_discarded() {
        let mut asm = StreamAssembler::new();
        let f = frame(10);
        let mut stream = vec![0xFF, 0xEE, 0x00]; // junk
        stream.extend_from_slice(&f);
        let (frames, _) = asm.ingest(&stream, 0);
        assert_eq!(frames.len(), 1);
        assert_eq!(frames[0].0, f);
    }

    #[test]
    fn hard_cap_resets() {
        let mut asm = StreamAssembler::new();
        // A v2 header claiming a payload beyond the framed-file cap is
        // unrecoverable garbage → stream reset.
        let mut evil = vec![2u8, 0x02, 7];
        evil.extend_from_slice(&[0u8; 8]); // timestamp
        evil.push(0); // flags
        evil.extend_from_slice(&2_000_000u32.to_be_bytes()); // payload length > cap
        evil.extend_from_slice(&[0x11u8; 8]); // sender (completes the header)
        let (_, reset) = asm.ingest(&evil, 0);
        assert!(reset);
        assert_eq!(asm.buffered(), 0);

        // Byte-stream junk (no frame start) is discarded without a reset.
        let mut asm2 = StreamAssembler::new();
        let (_, reset2) = asm2.ingest(&[0x99u8; 4096], 0);
        assert!(!reset2);
        assert_eq!(asm2.buffered(), 0);
    }
}
