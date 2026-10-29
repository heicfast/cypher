//! Mesh topology tracking — gossiped neighbor claims, bidirectionally
//! confirmed edges, and BFS route computation for source routing.

use std::collections::HashMap;

use crate::consts;

/// A node's neighbor set plus the highest packet version it has been
/// observed speaking.
#[derive(Clone, Debug, Default)]
struct NodeInfo {
    neighbors: Vec<[u8; 8]>,
    last_seen_s: u64,
    /// Highest packet version > 1 observed (v1-only nodes are never used as
    /// source-route hops).
    observed_version: u8,
}

#[derive(Default)]
pub struct MeshTopologyTracker {
    nodes: HashMap<[u8; 8], NodeInfo>,
}

impl MeshTopologyTracker {
    pub fn new() -> Self {
        Self::default()
    }

    /// Record a verified announce's neighbor claims (≤10 neighbors).
    pub fn update_from_announce(&mut self, node: [u8; 8], neighbors: &[[u8; 8]], now_s: u64) {
        let info = self.nodes.entry(node).or_default();
        info.last_seen_s = now_s;
        info.neighbors = neighbors
            .iter()
            .filter(|n| **n != node) // self-loops removed
            .take(consts::TOPOLOGY_MAX_NEIGHBORS_PER_ANNOUNCE)
            .copied()
            .collect();
    }

    /// Record that `node` was observed speaking packet `version`.
    pub fn record_observed_version(&mut self, node: [u8; 8], version: u8, now_s: u64) {
        if version <= 1 {
            return;
        }
        let info = self.nodes.entry(node).or_default();
        info.last_seen_s = now_s;
        info.observed_version = info.observed_version.max(version);
    }

    /// Drop nodes older than the freshness threshold (60 s).
    pub fn prune(&mut self, now_s: u64) {
        let cutoff = now_s.saturating_sub(consts::TOPOLOGY_FRESHNESS_S);
        self.nodes.retain(|_, info| info.last_seen_s >= cutoff);
    }

    /// BFS over **bidirectionally confirmed** edges (A claims B AND B claims
    /// A, both fresh), restricted to v2-speaking nodes. Returns the
    /// intermediate hops (excluding origin and destination) or `None`.
    pub fn compute_route(
        &self,
        origin: [u8; 8],
        destination: [u8; 8],
        max_hops: usize,
        now_s: u64,
    ) -> Option<Vec<[u8; 8]>> {
        let cutoff = now_s.saturating_sub(consts::TOPOLOGY_FRESHNESS_S);
        let speaks_v2 = |id: &[u8; 8]| -> bool {
            self.nodes
                .get(id)
                .map(|n| n.observed_version >= 2 && n.last_seen_s >= cutoff)
                .unwrap_or(false)
        };

        // BFS from origin.
        let mut parent: HashMap<[u8; 8], [u8; 8]> = HashMap::new();
        let mut visited: HashMap<[u8; 8], u32> = HashMap::new();
        visited.insert(origin, 0);
        let mut frontier = vec![origin];

        while let Some(current) = frontier.pop() {
            let current_depth = visited[&current];
            let Some(info) = self.nodes.get(&current) else {
                continue;
            };
            if info.last_seen_s < cutoff {
                continue;
            }
            for neighbor in &info.neighbors {
                if visited.contains_key(neighbor) {
                    continue;
                }
                // Edge must be bidirectionally confirmed and fresh.
                let confirmed = self
                    .nodes
                    .get(neighbor)
                    .map(|n| n.last_seen_s >= cutoff && n.neighbors.contains(&current))
                    .unwrap_or(false);
                if !confirmed {
                    continue;
                }
                // Every intermediate hop (and the destination) must speak v2.
                if *neighbor != destination && !speaks_v2(neighbor) {
                    continue;
                }
                if *neighbor == destination {
                    // Reconstruct the path.
                    let mut path = vec![*neighbor];
                    let mut node = current;
                    while node != origin {
                        path.push(node);
                        node = parent[&node];
                    }
                    path.reverse(); // intermediate hops, origin excluded
                    path.pop(); // drop destination
                    if path.len() <= max_hops {
                        return Some(path);
                    }
                    return None;
                }
                let depth = current_depth + 1;
                if depth as usize > max_hops + 1 {
                    continue;
                }
                visited.insert(*neighbor, depth);
                parent.insert(*neighbor, current);
                frontier.push(*neighbor);
            }
        }
        None
    }

    /// Snapshot of the advisory topology graph (either endpoint claiming
    /// counts, since announces cap at 10 neighbors).
    pub fn snapshot_edges(&self) -> Vec<([u8; 8], [u8; 8])> {
        let mut edges = Vec::new();
        for (node, info) in &self.nodes {
            for neighbor in &info.neighbors {
                edges.push((*node, *neighbor));
            }
        }
        edges
    }

    pub fn node_count(&self) -> usize {
        self.nodes.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn id(n: u8) -> [u8; 8] {
        [n; 8]
    }

    #[test]
    fn bidirectional_confirmation_required() {
        let mut t = MeshTopologyTracker::new();
        // A claims B, but B doesn't claim A.
        t.update_from_announce(id(1), &[id(2)], 1000);
        t.update_from_announce(id(2), &[], 1000);
        assert!(t.compute_route(id(1), id(2), 4, 1001).is_none());
        // Both directions confirmed.
        t.update_from_announce(id(2), &[id(1)], 1001);
        assert_eq!(t.compute_route(id(1), id(2), 4, 1001), Some(vec![]));
    }

    #[test]
    fn multihop_route_via_confirmed_edges() {
        let mut t = MeshTopologyTracker::new();
        // Line: 1 — 2 — 3 — 4, all bidirectional, all speaking v2.
        t.update_from_announce(id(1), &[id(2)], 1000);
        t.update_from_announce(id(2), &[id(1), id(3)], 1000);
        t.update_from_announce(id(3), &[id(2), id(4)], 1000);
        t.update_from_announce(id(4), &[id(3)], 1000);
        for n in 1..=4u8 {
            t.record_observed_version(id(n), 2, 1000);
        }
        assert_eq!(
            t.compute_route(id(1), id(4), 4, 1001),
            Some(vec![id(2), id(3)]),
            "intermediate hops only"
        );
        // Hop budget too small → None.
        assert!(t.compute_route(id(1), id(4), 1, 1001).is_none());
    }

    #[test]
    fn v1_only_nodes_not_used_as_hops() {
        let mut t = MeshTopologyTracker::new();
        t.update_from_announce(id(1), &[id(2)], 1000);
        t.update_from_announce(id(2), &[id(1), id(3)], 1000);
        t.update_from_announce(id(3), &[id(2), id(4)], 1000);
        t.update_from_announce(id(4), &[id(3)], 1000);
        // Only endpoints speak v2; node 2 is v1-only.
        t.record_observed_version(id(1), 2, 1000);
        t.record_observed_version(id(4), 2, 1000);
        assert!(t.compute_route(id(1), id(4), 4, 1001).is_none());
    }

    #[test]
    fn stale_nodes_pruned() {
        let mut t = MeshTopologyTracker::new();
        // Only the announced node creates an entry (neighbors are claims).
        t.update_from_announce(id(1), &[id(2)], 0);
        assert_eq!(t.node_count(), 1);
        t.update_from_announce(id(2), &[id(1)], 1000);
        assert_eq!(t.node_count(), 2);
        // Pruning at 1001 keeps the fresh node, drops the stale one.
        t.prune(1001);
        assert_eq!(t.node_count(), 1);
        t.prune(1000 + consts::TOPOLOGY_FRESHNESS_S + 1);
        assert_eq!(t.node_count(), 0);
    }

    #[test]
    fn self_loops_removed_and_neighbor_cap() {
        let mut t = MeshTopologyTracker::new();
        let many: Vec<[u8; 8]> = (0..15).map(|i| [i; 8]).collect();
        t.update_from_announce(id(1), &many, 1000);
        let info = t.nodes.get(&id(1)).unwrap();
        assert_eq!(
            info.neighbors.len(),
            consts::TOPOLOGY_MAX_NEIGHBORS_PER_ANNOUNCE
        );
        assert!(!info.neighbors.contains(&id(1)));
    }
}
