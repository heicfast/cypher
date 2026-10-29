//! Per-peer pending message queues — exact port of bitchat's
//! `BLENoiseSessionQueues` (bitchat/Services/BLENoiseSessionQueues.swift).
//!
//! Two independent FIFO maps per peer — private messages and typed
//! payloads — with one-shot atomic drain and failure re-queueing at the
//! front so order is maintained across retries.

use crate::PeerId;
use std::collections::HashMap;

/// A queued private chat message awaiting session establishment.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PendingPrivateMessage {
    pub content: String,
    pub message_id: String,
}

/// A queued typed binary payload (private media, receipts, proofs).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PendingTypedPayload {
    pub payload: Vec<u8>,
    pub transfer_id: Option<String>,
}

#[derive(Debug, Default)]
pub struct PendingQueues {
    private_messages: HashMap<PeerId, Vec<PendingPrivateMessage>>,
    typed_payloads: HashMap<PeerId, Vec<PendingTypedPayload>>,
}

impl PendingQueues {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn is_empty(&self) -> bool {
        self.private_messages.is_empty() && self.typed_payloads.is_empty()
    }

    pub fn remove_all(&mut self) {
        self.private_messages.clear();
        self.typed_payloads.clear();
    }

    // ---- private messages ---------------------------------------------------

    pub fn append_private_message(
        &mut self,
        peer: &PeerId,
        message: PendingPrivateMessage,
    ) {
        self.private_messages.entry(peer.clone()).or_default().push(message);
    }

    /// One-shot drain: returns the whole FIFO and removes the peer's key;
    /// a second take yields empty (Swift `takePrivateMessages`).
    pub fn take_private_messages(&mut self, peer: &PeerId) -> Vec<PendingPrivateMessage> {
        self.private_messages.remove(peer).unwrap_or_default()
    }

    /// Re-queue failed sends at the FRONT so they precede newer messages
    /// (Swift `prependPrivateMessages`).
    pub fn prepend_private_messages(
        &mut self,
        peer: &PeerId,
        messages: Vec<PendingPrivateMessage>,
    ) {
        let entry = self.private_messages.entry(peer.clone()).or_default();
        let mut combined = messages;
        combined.append(entry);
        *entry = combined;
    }

    // ---- typed payloads -----------------------------------------------------

    pub fn append_typed_payload(&mut self, peer: &PeerId, payload: PendingTypedPayload) {
        self.typed_payloads.entry(peer.clone()).or_default().push(payload);
    }

    /// One-shot drain (Swift `takeTypedPayloads`).
    pub fn take_typed_payloads(&mut self, peer: &PeerId) -> Vec<PendingTypedPayload> {
        self.typed_payloads.remove(peer).unwrap_or_default()
    }

    /// Whether any peer holds a payload for this transfer (Swift
    /// `containsTypedPayload(transferId:)`).
    pub fn contains_typed_payload(&self, transfer_id: &str) -> bool {
        self.typed_payloads
            .values()
            .flatten()
            .any(|p| p.transfer_id.as_deref() == Some(transfer_id))
    }

    /// Remove the first matching payload across peers; returns whether one
    /// was removed — idempotent cancel before drain (Swift
    /// `removeTypedPayload(transferId:)`).
    pub fn remove_typed_payload(&mut self, transfer_id: &str) -> bool {
        for queue in self.typed_payloads.values_mut() {
            if let Some(pos) = queue
                .iter()
                .position(|p| p.transfer_id.as_deref() == Some(transfer_id))
            {
                queue.remove(pos);
                return true;
            }
        }
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn peer(n: u8) -> PeerId {
        PeerId::new(format!("{n:016x}"))
    }

    #[test]
    fn fifo_drain_is_one_shot() {
        let mut q = PendingQueues::new();
        let p = peer(1);
        q.append_private_message(&p, PendingPrivateMessage { content: "one".into(), message_id: "m1".into() });
        q.append_private_message(&p, PendingPrivateMessage { content: "two".into(), message_id: "m2".into() });
        let drained = q.take_private_messages(&p);
        assert_eq!(drained.len(), 2);
        assert_eq!(drained[0].content, "one");
        assert_eq!(drained[1].content, "two");
        // Second take is empty — draining is one-shot.
        assert!(q.take_private_messages(&p).is_empty());
    }

    #[test]
    fn prepend_restores_order_across_retries() {
        let mut q = PendingQueues::new();
        let p = peer(1);
        q.append_private_message(&p, PendingPrivateMessage { content: "newer".into(), message_id: "m3".into() });
        // Two sends failed after the newer one was queued; they go back at
        // the front, in their original relative order.
        q.prepend_private_messages(
            &p,
            vec![
                PendingPrivateMessage { content: "older-1".into(), message_id: "m1".into() },
                PendingPrivateMessage { content: "older-2".into(), message_id: "m2".into() },
            ],
        );
        let drained = q.take_private_messages(&p);
        let contents: Vec<&str> = drained.iter().map(|m| m.content.as_str()).collect();
        assert_eq!(contents, vec!["older-1", "older-2", "newer"]);
    }

    #[test]
    fn private_messages_and_typed_payloads_drain_independently() {
        let mut q = PendingQueues::new();
        let p = peer(7);
        q.append_private_message(&p, PendingPrivateMessage { content: "dm".into(), message_id: "m".into() });
        q.append_typed_payload(&p, PendingTypedPayload { payload: vec![0x20, 1], transfer_id: Some("t1".into()) });
        let typed = q.take_typed_payloads(&p);
        assert_eq!(typed.len(), 1);
        // Private message survives the typed drain.
        let dms = q.take_private_messages(&p);
        assert_eq!(dms.len(), 1);
        assert_eq!(dms[0].content, "dm");
    }

    #[test]
    fn typed_payload_cancel_is_idempotent() {
        let mut q = PendingQueues::new();
        let p = peer(2);
        q.append_typed_payload(&p, PendingTypedPayload { payload: vec![9], transfer_id: Some("transfer-A".into()) });
        assert!(q.contains_typed_payload("transfer-A"));
        assert!(q.remove_typed_payload("transfer-A"));
        assert!(!q.remove_typed_payload("transfer-A")); // second cancel: no-op
        assert!(!q.contains_typed_payload("transfer-A"));
        assert!(q.take_typed_payloads(&p).is_empty());
    }
}
