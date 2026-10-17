#!/usr/bin/env python3
"""Count live node-to-node TCP paths without exposing internal addresses.

Run on each DB VM immediately before and during a controlled partition. The
Patroni cluster view supplies peers; no address is accepted from the caller.
"""

import ipaddress
import json
import socket
import urllib.request


def main():
    with urllib.request.urlopen("http://127.0.0.1:8008/patroni", timeout=3) as response:
        local = json.load(response)
    with urllib.request.urlopen("http://127.0.0.1:8008/cluster", timeout=3) as response:
        members = json.load(response).get("members", [])
    name = local.get("patroni", {}).get("name")
    peers = [member for member in members if member.get("name") != name]
    if len(members) != 3 or len(peers) != 2:
        raise RuntimeError("not_three_members")
    counts = {2379: 0, 8008: 0, 5432: 0}
    for peer in peers:
        address = ipaddress.ip_address(peer["host"])
        if not isinstance(address, ipaddress.IPv4Address) or not address.is_private:
            raise RuntimeError("invalid_private_peer")
        for port in counts:
            try:
                with socket.create_connection((str(address), port), timeout=0.6):
                    counts[port] += 1
            except OSError:
                pass
    print(f"peer_count=2 etcd_tcp_up={counts[2379]} patroni_tcp_up={counts[8008]} db_tcp_up={counts[5432]}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError) as error:
        print(f"reachability_failed={type(error).__name__}")
        raise SystemExit(1)
