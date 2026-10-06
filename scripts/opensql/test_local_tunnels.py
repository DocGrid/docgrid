"""Verify local tunnel routing and Agent behavior without opening a GCP connection."""

import contextlib
import importlib.util
import io
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("local_tunnels.py")
SPEC = importlib.util.spec_from_file_location("local_tunnels", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Local tunnel module cannot be loaded")
TUNNELS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TUNNELS)


def example_urls():
    return {"OPENSQL_APP_JDBC_URL":
            "jdbc:postgresql://127.0.0.1:16432,127.0.0.1:16433/docgrid?loadBalanceHosts=true",
            "OPENSQL_MIGRATION_JDBC_URL":
            "jdbc:postgresql://127.0.0.1:15431,127.0.0.1:15432,127.0.0.1:15433/docgrid?targetServerType=primary"}


# Checks only local routing and Agent construction; no cloud or launchd process is started.
class LocalTunnelsTest(unittest.TestCase):
    """Keep each logical node bound to its DB and optional proxy port only."""

    @patch.object(TUNNELS, "read_env_urls", side_effect=example_urls)
    def test_port_plan_matches_three_node_topology(self, _read):
        self.assertEqual({"node1": [(15431, 5432)],
                          "node2": [(15432, 5432), (16432, 6432)],
                          "node3": [(15433, 5432), (16433, 6432)]},
                         TUNNELS.port_plan())

    def test_non_loopback_and_duplicate_ports_are_rejected(self):
        with self.assertRaises(SystemExit):
            TUNNELS.loopback_ports("jdbc:postgresql://192.0.2.1:6432/docgrid", 1)
        with patch.object(TUNNELS, "read_env_urls", return_value={
                **example_urls(), "OPENSQL_APP_JDBC_URL":
                "jdbc:postgresql://localhost:15431,localhost:16433/docgrid"}):
            with self.assertRaises(SystemExit):
                TUNNELS.port_plan()

    @patch.object(TUNNELS, "port_plan", return_value={"node2": [(15432, 5432), (16432, 6432)]})
    @patch.object(TUNNELS, "read_config", return_value={
        "project": "example-project", "zone": "example-zone", "node2": "example-node2"})
    @patch.object(TUNNELS.subprocess, "run")
    def test_node2_forwards_both_ports_without_shell(self, run, _config, _plan):
        run.return_value.returncode = 0
        with patch.dict(TUNNELS.os.environ, {"DOCGRID_GCLOUD_BIN": "/bin/gcloud",
                                            "DOCGRID_TUNNEL_QUIET": "1"}):
            self.assertEqual(0, TUNNELS.serve("node2"))
        command = run.call_args.args[0]
        self.assertIn("--tunnel-through-iap", command)
        self.assertIn("UseKeychain=yes", command)
        self.assertIn("127.0.0.1:15432:127.0.0.1:5432", command)
        self.assertIn("127.0.0.1:16432:127.0.0.1:6432", command)
        self.assertEqual(subprocess.DEVNULL, run.call_args.kwargs["stderr"])
        self.assertFalse(run.call_args.kwargs.get("shell", False))

    def test_launch_agent_restarts_without_persisting_gcloud_output(self):
        agent = TUNNELS.make_plist("node1", "/bin/gcloud")
        self.assertTrue(agent["RunAtLoad"])
        self.assertTrue(agent["KeepAlive"])
        self.assertEqual("/dev/null", agent["StandardErrorPath"])
        self.assertEqual(["serve", "node1"], agent["ProgramArguments"][-2:])

    @patch.object(TUNNELS, "require_macos")
    @patch.object(TUNNELS, "loaded", return_value=True)
    @patch.object(TUNNELS, "port_plan", return_value={
        "node1": [(15431, 5432)], "node2": [(15432, 5432), (16432, 6432)],
        "node3": [(15433, 5432), (16433, 6432)]})
    @patch.object(TUNNELS.subprocess, "run")
    def test_status_fails_when_agent_has_no_listening_ports(self, run, _plan, _loaded, _macos):
        run.return_value.returncode = 1
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(1, TUNNELS.status())
        self.assertIn("터널이 연결되지 않았습니다", output.getvalue())
        self.assertIn("serve node1", output.getvalue())

        run.return_value.returncode = 0
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, TUNNELS.status())


if __name__ == "__main__":
    unittest.main()
