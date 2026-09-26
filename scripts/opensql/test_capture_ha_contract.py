"""Check that OpenSQL contract snapshots are deterministic and credential-free."""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("capture_ha_contract.py")
SPEC = importlib.util.spec_from_file_location("capture_ha_contract", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("HA 계약 수집기를 읽을 수 없습니다")
CONTRACT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONTRACT)


class ContractSnapshotTest(unittest.TestCase):
    """Guard allowlisted extraction and A/B contract comparisons before remote use."""

    def setUp(self):
        """Place realistic secrets next to public settings in an isolated config."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.config = self.root / "openproxy.toml"
        self.config.write_text("""
[general]
prepared_statements_cache_size = 1000
renew_interval = 5000
admin_password = "do-not-publish-admin"

[pools.docgrid]
pool_mode = "transaction"
query_parser_enabled = true
query_parser_read_write_splitting = true

[pools.docgrid.users.0]
username = "docgrid_app"
password = "do-not-publish-app"
pool_size = 5

[pools.docgrid.shards.0]
servers = [
  ["192.0.2.1", 5432, "auto"],
  ["192.0.2.2", 5432, "auto"]
]
database = "docgrid"
use_patroni = true
patroni_port = "8008"
""", encoding="utf-8")

    def test_proxy_output_omits_passwords_and_addresses(self):
        """Read only contract keys, even when secrets share the same section."""
        settings = CONTRACT.proxy_settings(self.config)
        serialized = json.dumps(settings)
        self.assertEqual(1000, settings["general"]["prepared_statements_cache_size"])
        self.assertEqual("transaction", settings["pools.docgrid"]["pool_mode"])
        self.assertNotIn("do-not-publish", serialized)
        self.assertNotIn("192.0.2", serialized)

    def test_duplicate_or_sensitive_allowlisted_value_is_rejected(self):
        """Do not silently choose one duplicate or print an identifying value."""
        self.config.write_text(self.config.read_text() + "\n[pools.docgrid]\npool_mode = \"session\"\n")
        with self.assertRaisesRegex(CONTRACT.ContractError, "중복"):
            CONTRACT.proxy_settings(self.config)
        self.config.write_text(self.config.read_text().replace(
            'pool_mode = "transaction"', 'pool_mode = "192.0.2.1"').replace(
            '\n[pools.docgrid]\npool_mode = "session"\n', ''))
        with self.assertRaisesRegex(CONTRACT.ContractError, "공개"):
            CONTRACT.proxy_settings(self.config)

    def test_patroni_top_level_only_and_missing_keys_are_explicit(self):
        """A nested PostgreSQL option must not impersonate a dynamic HA setting."""
        dynamic = CONTRACT.patroni_settings("""
loop_wait: 10
primary_start_timeout: 300
failsafe_mode: false
postgresql:
  parameters:
    password: do-not-publish
""")
        self.assertEqual(300, dynamic["primary_start_timeout"])
        self.assertFalse(dynamic["failsafe_mode"])
        self.assertIsNone(dynamic["ttl"])
        self.assertNotIn("do-not-publish", json.dumps(dynamic))

    def test_assembly_is_order_independent_and_rejects_proxy_drift(self):
        """One canonical SHA-256 represents the same three sanitized nodes."""
        paths = []
        for node in ("node1", "node2", "node3"):
            path = self.root / f"{node}.json"
            data = {"schema_version": 1, "node": node,
                    "os": {"id": "rocky", "version_id": "9.7", "architecture": "x86_64"},
                    "patroni_dynamic": {"ttl": 30}}
            if node != "node1":
                data["openproxy"] = {"pool_mode": "transaction"}
                data["versions"] = {"openproxy": "openproxy 1.1.3"}
            path.write_text(json.dumps(data), encoding="utf-8")
            paths.append(path)
        first = CONTRACT.assemble(paths)
        self.assertEqual(first, CONTRACT.assemble(list(reversed(paths))))
        changed = json.loads(paths[2].read_text())
        changed["openproxy"]["pool_mode"] = "session"
        paths[2].write_text(json.dumps(changed), encoding="utf-8")
        with self.assertRaisesRegex(CONTRACT.ContractError, "설정이 다릅니다"):
            CONTRACT.assemble(paths)

    def test_assembly_rejects_os_and_patroni_drift(self):
        """A snapshot from a different OS or DCS state cannot prove one contract."""
        paths = []
        for node in ("node1", "node2", "node3"):
            path = self.root / f"{node}.json"
            path.write_text(json.dumps({"schema_version": 1, "node": node,
                "os": {"id": "rocky", "version_id": "9.7", "architecture": "x86_64"},
                "patroni_dynamic": {"ttl": 30},
                **({"openproxy": {"pool_mode": "transaction"}} if node != "node1" else {})}),
                encoding="utf-8")
            paths.append(path)
        changed = json.loads(paths[2].read_text())
        changed["os"]["architecture"] = "aarch64"
        paths[2].write_text(json.dumps(changed), encoding="utf-8")
        with self.assertRaisesRegex(CONTRACT.ContractError, "Rocky Linux"):
            CONTRACT.assemble(paths)
        changed["os"]["architecture"] = "x86_64"
        changed["patroni_dynamic"]["ttl"] = 60
        paths[2].write_text(json.dumps(changed), encoding="utf-8")
        with self.assertRaisesRegex(CONTRACT.ContractError, "Patroni"):
            CONTRACT.assemble(paths)

    def test_admin_snapshot_whitelists_effective_config_and_counters(self):
        """Do not copy credentials or backend addresses from administrator tables."""
        general = self.config.read_text().replace(
            'admin_password = "do-not-publish-admin"',
            'admin_port = 6433\nport = 6432\nadmin_username = "admin"\n'
            'admin_password = "do-not-publish-admin"')
        self.config.write_text(general, encoding="utf-8")
        credentials = CONTRACT.admin_credentials(self.config)
        self.assertEqual(6432, credentials["port"])
        rows = {
            "SHOW CONFIG": [{"key": "pools.docgrid.prepared_statements_cache_size", "value": "0"},
                            {"key": "pools.docgrid.pool_mode", "value": '"Transaction"'},
                            {"key": "admin_password", "value": "do-not-publish-admin"}],
            "SHOW STATS": [{"database": "docgrid", "instance": "docgrid_shard_0_replica_0",
                            "total_query_count": "7", "total_xact_count": "5", "total_errors": "0",
                            "user": "do-not-publish-app"}],
            "SHOW SERVERS": [{"database_name": "docgrid", "address_id": "docgrid_shard_0_replica_0",
                              "prepare_cache_hit": "3", "prepare_cache_miss": "1",
                              "prepare_cache_eviction": "0", "prepare_cache_size": "1",
                              "user": "do-not-publish-app"}],
        }
        with patch.object(CONTRACT, "admin_rows", side_effect=lambda _root, _auth, sql: rows[sql]):
            with patch.object(CONTRACT, "admin_credentials", return_value=credentials):
                data = CONTRACT.admin_snapshot(SimpleNamespace(node="node2", install_root=self.root))
        self.assertEqual(0, data["effective_config"]["pools.docgrid.prepared_statements_cache_size"])
        self.assertEqual("Transaction", data["effective_config"]["pools.docgrid.pool_mode"])
        self.assertEqual(7, data["stats"][0]["queries"])
        self.assertNotIn("do-not-publish", json.dumps(data))


if __name__ == "__main__":
    unittest.main()
