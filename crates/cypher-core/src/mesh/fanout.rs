//! Deterministic fanout selection — the port of `BLEFanoutSelector`.
//!
//! Broadcasts relay to a logarithmic subset of links (scored by
//! `SHA-256(messageID ‖ "::" ‖ linkId)`, k smallest), so two peers that see
//! the same message choose independent-but-stable subsets. Announces,
//! fragments and sync packets use full fanout; directed traffic goes to the
//! links bound to the target peer.

use crate::util::sha256;
use std::collections::HashMap;

/// A candidate link for fanout.
#[derive(Clone, Debug)]
pub struct FanoutLink {
    pub link: crate::mesh::link::LinkId,
    pub direction: crate::mesh::link::LinkDirection,
    /// Peer the link is bound to (None when unbound).
    pub bound_peer: Option<crate::PeerId>,
}

/// Subset size for broadcasts: `count ≤ 2 → count`, else
/// `max(1, ⌈log2(count−1)⌉ + 1)`.
pub fn subset_size(count: usize) -> usize {
    if count <= 2 {
        return count;
    }
    let bits = (usize::BITS - (count - 1).leading_zeros()) as usize; // ceil(log2(n-1))
    (bits + 1).max(1)
}

/// Select the links a broadcast should be written to. `excluded` carries the
/// ingress link (split horizon) and any already-written links.
pub fn select_broadcast_links(
    message_id: &str,
    candidates: &[FanoutLink],
    excluded: &[crate::mesh::link::LinkId],
) -> Vec<crate::mesh::link::LinkId> {
    let allowed: Vec<&FanoutLink> = candidates
        .iter()
        .filter(|c| !excluded.contains(&c.link))
        .collect();
    if allowed.is_empty() {
        return Vec::new();
    }
    let k = subset_size(allowed.len()).min(allowed.len());
    if k >= allowed.len() {
        return allowed.into_iter().map(|c| c.link).collect();
    }
    // Deterministic per-message selection: k smallest SHA-256(messageID "::" linkID).
    let mut scored: Vec<(Vec<u8>, crate::mesh::link::LinkId)> = allowed
        .iter()
        .map(|c| {
            let mut input = format!("{}::{}", message_id, c.link).into_bytes();
            let digest = sha256(&input);
            input.clear();
            (digest.to_vec(), c.link)
        })
        .collect();
    scored.sort_by(|a, b| a.0.cmp(&b.0));
    scored.into_iter().take(k).map(|(_, link)| link).collect()
}

/// Select the links a directed packet should be written to: every link bound
/// to the target peer (one per peer after collapse).
pub fn select_directed_links(
    candidates: &[FanoutLink],
    target: &crate::PeerId,
) -> Vec<crate::mesh::link::LinkId> {
    let mut seen_peers: HashMap<crate::PeerId, crate::mesh::link::LinkId> = HashMap::new();
    for c in candidates {
        if let Some(peer) = &c.bound_peer {
            if peer == target {
                seen_peers.entry(peer.clone()).or_insert(c.link);
            }
        }
    }
    seen_peers.into_values().collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mesh::link::LinkDirection;

    fn links(n: u64) -> Vec<FanoutLink> {
        (0..n)
            .map(|i| FanoutLink {
                link: i,
                direction: LinkDirection::Central,
                bound_peer: Some(crate::PeerId::new(format!("{i:016x}"))),
            })
            .collect()
    }

    #[test]
    fn subset_sizes() {
        // Matches bitchat's subsetSize: k = min(count, max(1, bits(count-1)+1))
        // where bits = bit-length of count-1.
        assert_eq!(subset_size(0), 0);
        assert_eq!(subset_size(1), 1);
        assert_eq!(subset_size(2), 2);
        assert_eq!(subset_size(3), 3); // bits(2)=2 → 3
        assert_eq!(subset_size(4), 3); // bits(3)=2 → 3
        assert_eq!(subset_size(7), 4); // bits(6)=3 → 4
        assert_eq!(subset_size(9), 5); // bits(8)=4 → 5
        assert_eq!(subset_size(16), 5); // bits(15)=4 → 5
        assert_eq!(subset_size(17), 6); // bits(16)=5 → 6
    }

    #[test]
    fn deterministic_selection() {
        let candidates = links(9);
        let a = select_broadcast_links("msg-1", &candidates, &[]);
        let b = select_broadcast_links("msg-1", &candidates, &[]);
        assert_eq!(a, b);
        assert_eq!(a.len(), subset_size(9));
        // Different message → (very likely) different subset.
        let c = select_broadcast_links("msg-2", &candidates, &[]);
        assert_eq!(c.len(), subset_size(9));
        assert_ne!(a, c);
    }

    #[test]
    fn ingress_link_excluded() {
        let candidates = links(9);
        let with_exclusion = select_broadcast_links("msg-1", &candidates, &[3, 5]);
        assert!(!with_exclusion.contains(&3));
        assert!(!with_exclusion.contains(&5));
    }

    #[test]
    fn small_sets_take_everything() {
        let candidates = links(2);
        let selected = select_broadcast_links("msg-1", &candidates, &[]);
        assert_eq!(selected.len(), 2);
    }

    #[test]
    fn directed_selects_bound_links() {
        let target = crate::PeerId::new("0011223344556677");
        let candidates = vec![
            FanoutLink {
                link: 1,
                direction: LinkDirection::Central,
                bound_peer: Some(target.clone()),
            },
            FanoutLink {
                link: 2,
                direction: LinkDirection::Peripheral,
                bound_peer: Some(target.clone()),
            },
            FanoutLink {
                link: 3,
                direction: LinkDirection::Central,
                bound_peer: Some(crate::PeerId::new("ffeeddccbbaa9988")),
            },
            FanoutLink {
                link: 4,
                direction: LinkDirection::Central,
                bound_peer: None,
            },
        ];
        // Duplicate-link collapse: one link per bound peer (bitchat keeps a
        // single link per peer for directed sends).
        let selected = select_directed_links(&candidates, &target);
        assert_eq!(selected.len(), 1);
        assert!(selected.contains(&1) || selected.contains(&2));
    }
}
