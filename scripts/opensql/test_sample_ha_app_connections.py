"""Verify that the HA sampler reports connection counts without exporting addresses."""

import importlib.util
import tempfile
import unittest
from pathlib import Path


SOURCE = Path(__file__).with_name("sample_ha_app_connections.py")
SPEC = importlib.util.spec_from_file_location("sample_ha_app_connections", SOURCE)
SAMPLER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SAMPLER)


class HaConnectionSamplerTest(unittest.TestCase):
    """Cover JDBC endpoint validation and JVM-inode-bound TCP route counting."""

    def test_two_proxy_endpoints_are_read_without_output(self):
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / ".env"
            env_file.write_text(
                "OPENSQL_APP_JDBC_URL=jdbc:postgresql://192.0.2.1:6432,198.51.100.2:6432/docgrid\n"
                "JWT_SECRET=test-only-placeholder\n", encoding="utf-8",
            )
            self.assertEqual({
                "proxy_a": ("192.0.2.1", 6432),
                "proxy_b": ("198.51.100.2", 6432),
            }, SAMPLER.proxy_targets(env_file))

    def test_single_or_duplicate_endpoint_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / ".env"
            for hosts in ("192.0.2.1:6432", "192.0.2.1:6432,192.0.2.1:6432"):
                env_file.write_text(f"OPENSQL_APP_JDBC_URL=jdbc:postgresql://{hosts}/docgrid\n")
                with self.assertRaises(ValueError):
                    SAMPLER.proxy_targets(env_file)

    def test_only_owned_established_proxy_sockets_are_counted(self):
        header = "sl local_address rem_address st tx_queue rx_queue tr tm retr uid timeout inode"
        lines = [
            header,
            "0: 0100007F:ABCD 010200C0:1920 01 0:0 00:0 0 0 0 42 0",
            "1: 0100007F:ABCE 026433C6:1920 01 0:0 00:0 0 0 0 43 0",
            "2: 0100007F:ABCF 010200C0:1920 06 0:0 00:0 0 0 0 44 0",
            "3: 0100007F:ABD0 010200C0:1920 01 0:0 00:0 0 0 0 45 0",
        ]
        result = SAMPLER.match_proxy_tcp_rows(
            lines, {"42", "43", "44"},
            {"proxy_a": ("192.0.2.1", 6432), "proxy_b": ("198.51.100.2", 6432)},
        )
        self.assertEqual({"proxy_a": {"42"}, "proxy_b": {"43"}}, result)

    def test_ipv4_mapped_tcp6_socket_is_counted(self):
        header = "sl local_address rem_address st tx_queue rx_queue tr tm retr uid timeout inode"
        lines = [
            header,
            "0: 00000000000000000000000001000000:ABCD "
            "0000000000000000FFFF0000010200C0:1920 01 0:0 00:0 0 0 0 52 0",
        ]
        result = SAMPLER.match_proxy_tcp_rows(
            lines, {"52"},
            {"proxy_a": ("192.0.2.1", 6432), "proxy_b": ("198.51.100.2", 6432)},
            ipv6=True,
        )
        self.assertEqual({"proxy_a": {"52"}, "proxy_b": set()}, result)


if __name__ == "__main__":
    unittest.main()
