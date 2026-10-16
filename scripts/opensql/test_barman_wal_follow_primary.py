"""Regression checks for quorum and Barman fail-closed WAL receiver changes."""

import unittest
from unittest.mock import call, patch

import barman_wal_follow_primary as follower


NODES = [
    {"model": f"node-{index}", "metrics_url": f"http://10.0.0.{index}:18008/metrics"}
    for index in range(1, 4)
]
CONFIG = {"server": "backup-cluster", "nodes": NODES}


class BarmanWalFollowPrimaryTest(unittest.TestCase):
    """Keep ambiguous elections from reaching any Barman mutation."""

    def test_labeled_patroni_metrics_are_parsed_from_direct_connection(self):
        response = unittest.mock.Mock(status=200)
        response.read.return_value = (b'patroni_primary{scope="example"} 1\n'
                                      b'patroni_postgres_running{scope="example"} 1\n')
        with patch.object(follower, "HTTPConnection") as connection:
            connection.return_value.getresponse.return_value = response
            self.assertEqual((1, 1), follower.read_role(NODES[0]["metrics_url"]))
            connection.assert_called_once_with("10.0.0.1", 18008, timeout=2)
            connection.return_value.request.assert_called_once_with("GET", "/metrics")

    def test_quorum_with_one_unreachable_node_elects_primary(self):
        with patch.object(follower, "read_role", side_effect=[(0, 1), OSError(), (1, 1)]):
            self.assertEqual((2, 2), follower.elect_primary(NODES))

    def test_no_quorum_rejects_election(self):
        with patch.object(follower, "read_role", side_effect=[OSError(), OSError(), (1, 1)]):
            with self.assertRaisesRegex(ValueError, "quorum"):
                follower.elect_primary(NODES)

    def test_non_running_second_node_does_not_make_quorum(self):
        with patch.object(follower, "read_role", side_effect=[(1, 1), (0, 0), OSError()]):
            with self.assertRaisesRegex(ValueError, "quorum"):
                follower.elect_primary(NODES)

    def test_two_primaries_reject_election(self):
        with patch.object(follower, "read_role", side_effect=[(1, 1), (1, 1), (0, 1)]):
            with self.assertRaisesRegex(ValueError, "ambiguous"):
                follower.elect_primary(NODES)

    def test_config_validation_rejects_non_private_host(self):
        import json
        import tempfile
        from pathlib import Path

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"
            bad = {"server": "backup-cluster", "nodes": [*NODES[:2], {"model": "node-3", "metrics_url": "http://8.8.8.8:18008/metrics"}]}
            path.write_text(json.dumps(bad))
            with self.assertRaisesRegex(ValueError, "private"):
                follower.load_config(path)

    def test_aligned_receiver_does_not_stop_or_switch(self):
        settings = {"active_model": "node-1", "conninfo": "host=10.0.0.1 user=backup", "streaming_conninfo": "host=10.0.0.1 user=stream"}
        with (patch.object(follower, "elect_primary", return_value=(0, 3)),
              patch.object(follower, "effective_settings", return_value=settings),
              patch.object(follower, "require_success") as commands,
              patch.object(follower, "receiver_healthy", return_value=True)):
            result = follower.follow(CONFIG, dry_run=False)
        self.assertEqual("healthy", result["status"])
        self.assertFalse(result["switched"])
        commands.assert_called_once_with("cron")

    def test_switch_stops_receiver_then_verifies_new_target(self):
        old = {"active_model": "node-1", "conninfo": "host=10.0.0.1 user=backup", "streaming_conninfo": "host=10.0.0.1 user=stream"}
        new = {"active_model": "node-2", "conninfo": "host=10.0.0.2 user=backup", "streaming_conninfo": "host=10.0.0.2 user=stream"}
        with (patch.object(follower, "elect_primary", return_value=(1, 3)),
              patch.object(follower, "effective_settings", side_effect=[old, new]),
              patch.object(follower, "require_success") as commands,
              patch.object(follower, "receiver_healthy", return_value=True)):
            result = follower.follow(CONFIG, dry_run=False)
        self.assertTrue(result["switched"])
        self.assertEqual([call("config-switch", "backup-cluster", "node-2"),
                          call("receive-wal", "--stop", "backup-cluster"),
                          call("cron")], commands.call_args_list)

    def test_failed_switch_never_restarts_receiver(self):
        old = {"active_model": "node-1", "conninfo": "host=10.0.0.1", "streaming_conninfo": "host=10.0.0.1"}
        def fail_switch(*arguments):
            if arguments[0] == "config-switch":
                raise ValueError("switch failed")
        with (patch.object(follower, "elect_primary", return_value=(1, 3)),
              patch.object(follower, "effective_settings", return_value=old),
              patch.object(follower, "require_success", side_effect=fail_switch) as commands):
            with self.assertRaisesRegex(ValueError, "switch failed"):
                follower.follow(CONFIG, dry_run=False)
        commands.assert_called_once_with("config-switch", "backup-cluster", "node-2")

    def test_missing_model_keeps_existing_receiver_running(self):
        old = {"active_model": "None", "conninfo": "host=10.0.0.1", "streaming_conninfo": "host=10.0.0.1"}
        with (patch.object(follower, "elect_primary", return_value=(1, 3)),
              patch.object(follower, "effective_settings", return_value=old),
              patch.object(follower, "require_success", side_effect=ValueError("model missing")) as commands):
            with self.assertRaisesRegex(ValueError, "model missing"):
                follower.follow(CONFIG, dry_run=False)
        commands.assert_called_once_with("config-switch", "backup-cluster", "node-2")

    def test_model_initialization_on_same_host_does_not_stop_receiver(self):
        old = {"active_model": "None", "conninfo": "host=10.0.0.1", "streaming_conninfo": "host=10.0.0.1"}
        new = {**old, "active_model": "node-1"}
        with (patch.object(follower, "elect_primary", return_value=(0, 3)),
              patch.object(follower, "effective_settings", side_effect=[old, new]),
              patch.object(follower, "require_success") as commands,
              patch.object(follower, "receiver_healthy", return_value=True)):
            follower.follow(CONFIG, dry_run=False)
        self.assertEqual([call("config-switch", "backup-cluster", "node-1"), call("cron")], commands.call_args_list)


if __name__ == "__main__":
    unittest.main()
