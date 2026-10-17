#!/usr/bin/env python3
"""Check the partition guard's role and peer-address safety gates without nft."""

import io
import json
import unittest
from unittest.mock import patch

import primary_partition_guard as guard


class PrimaryPartitionGuardTest(unittest.TestCase):
    """Keep fault target selection limited to the current three-node Primary."""

    def responses(self, role="primary", peer_host="10.0.0.2"):
        local = {"role": role, "patroni": {"name": "node1"}}
        cluster = {"members": [
            {"name": "node1", "role": "leader", "state": "running", "host": "10.0.0.1"},
            {"name": "node2", "role": "replica", "state": "streaming", "host": peer_host},
            {"name": "node3", "role": "replica", "state": "streaming", "host": "10.0.0.3"},
        ]}
        return [io.BytesIO(json.dumps(value).encode()) for value in (local, cluster)]

    def test_selects_only_the_two_private_replica_addresses(self):
        with patch.object(guard.urllib.request, "urlopen", side_effect=self.responses()):
            self.assertEqual(guard.primary_peers(), ["10.0.0.2", "10.0.0.3"])

    def test_refuses_to_partition_a_replica(self):
        with patch.object(guard.urllib.request, "urlopen", side_effect=self.responses(role="replica")):
            with self.assertRaises(RuntimeError):
                guard.primary_peers()

    def test_refuses_a_nonprivate_peer_address(self):
        with patch.object(guard.urllib.request, "urlopen", side_effect=self.responses(peer_host="8.8.8.8")):
            with self.assertRaises(RuntimeError):
                guard.primary_peers()

    def test_run_id_cannot_inject_nft_syntax(self):
        with self.assertRaises(ValueError):
            guard.table_name("bad;delete table")


if __name__ == "__main__":
    unittest.main()
