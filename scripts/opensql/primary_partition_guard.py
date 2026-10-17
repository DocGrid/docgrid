#!/usr/bin/env python3
"""Bound a primary-only network partition with an independent host rollback timer.

Run as root on the current Primary. Peer addresses come from Patroni's live
three-member view; the program never prints or persists those addresses.
"""

import argparse
import ipaddress
import json
import re
import shutil
import subprocess
import sys
import time
import urllib.request


def command(*args, input_text=None, check=True):
    return subprocess.run(
        args, input=input_text, text=True, capture_output=True, check=check
    )


def table_name(run_id):
    if not re.fullmatch(r"[a-z][a-z0-9]{2,15}", run_id):
        raise ValueError("run ID must be 3-16 lowercase letters or digits")
    return f"dgpart_{run_id}"


def primary_peers():
    with urllib.request.urlopen("http://127.0.0.1:8008/patroni", timeout=5) as response:
        local = json.load(response)
    with urllib.request.urlopen("http://127.0.0.1:8008/cluster", timeout=5) as response:
        cluster = json.load(response)
    members = cluster.get("members", [])
    local_name = local.get("patroni", {}).get("name")
    leaders = [member for member in members if member.get("role") == "leader"]
    if local.get("role") != "primary" or len(members) != 3 or len(leaders) != 1:
        raise RuntimeError("local node must be the sole Primary of a three-member cluster")
    if leaders[0].get("name") != local_name:
        raise RuntimeError("local Patroni role and cluster leader disagree")
    replicas = [member for member in members if member.get("name") != local_name]
    # Patroni relabels the same streaming peers when synchronous mode is enabled.
    standby_roles = {"replica", "sync_standby", "quorum_standby"}
    if len(replicas) != 2 or any(member.get("role") not in standby_roles or member.get("state") != "streaming" for member in replicas):
        raise RuntimeError("both peer members must be streaming standbys")
    peers = []
    for member in replicas:
        address = ipaddress.ip_address(member["host"])
        if not isinstance(address, ipaddress.IPv4Address) or not address.is_private:
            raise RuntimeError("peer address is not a private IPv4 address")
        peers.append(str(address))
    if len(set(peers)) != 2:
        raise RuntimeError("peer addresses are not distinct")
    return peers


def exists(nft, table):
    return command(nft, "list", "table", "inet", table, check=False).returncode == 0


def arm(nft, table, seconds):
    unit = f"{table}_revert"
    # 1. Create an empty table before arming rollback; it cannot block traffic.
    command(nft, "add", "table", "inet", table)
    try:
        command("systemd-run", "--quiet", "--unit", unit, "--on-active", f"{seconds}s",
                nft, "delete", "table", "inet", table)
        active = command("systemctl", "is-active", f"{unit}.timer", check=False)
        if active.stdout.strip() != "active":
            raise RuntimeError("rollback timer is not active")
    except Exception:
        command(nft, "delete", "table", "inet", table, check=False)
        raise
    return unit


def revert(nft, table):
    # 2. Remove only this run's table, then cancel its pending rollback timer.
    command(nft, "delete", "table", "inet", table, check=False)
    command("systemctl", "stop", f"{table}_revert.timer", check=False)
    if exists(nft, table):
        raise RuntimeError("partition table remains after rollback")


def apply(nft, table, seconds):
    peers = primary_peers()
    arm(nft, table, seconds)
    # 3. Input and output drops at the Primary isolate both directions while
    # leaving app, controller, local OpenProxy, and replica-to-replica paths intact.
    rules = [
        f"add chain inet {table} input {{ type filter hook input priority -100; policy accept; }}",
        f"add chain inet {table} output {{ type filter hook output priority -100; policy accept; }}",
    ]
    for peer in peers:
        rules.append(f"add rule inet {table} input ip saddr {peer} counter drop")
        rules.append(f"add rule inet {table} output ip daddr {peer} counter drop")
    try:
        command(nft, "-f", "-", input_text="\n".join(rules) + "\n")
        if not exists(nft, table):
            raise RuntimeError("partition table was not installed")
    except Exception:
        revert(nft, table)
        raise
    print(f"partition_active=yes peers={len(peers)} rollback_seconds={seconds}")


def smoke(nft, table):
    # 4. Exercise the timer with an empty table before any traffic-blocking run.
    arm(nft, table, 5)
    deadline = time.monotonic() + 25
    while exists(nft, table) and time.monotonic() < deadline:
        time.sleep(0.5)
    if exists(nft, table):
        revert(nft, table)
        raise RuntimeError("automatic rollback did not remove the smoke table")
    result = command("systemctl", "show", f"{table}_revert.service", "-p", "Result", "--value")
    if result.stdout.strip() != "success":
        raise RuntimeError("rollback service did not succeed")
    print("automatic_rollback=passed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("plan", "smoke", "apply", "revert", "status"))
    parser.add_argument("run_id")
    parser.add_argument("--rollback-seconds", type=int, default=180)
    args = parser.parse_args()
    table = table_name(args.run_id)
    nft = shutil.which("nft")
    if not nft:
        raise RuntimeError("nft is unavailable")
    if args.action == "plan":
        print(f"primary_peer_count={len(primary_peers())}")
    elif args.action == "smoke":
        smoke(nft, table)
    elif args.action == "apply":
        if not 120 <= args.rollback_seconds <= 600:
            raise ValueError("rollback must be between 120 and 600 seconds")
        apply(nft, table, args.rollback_seconds)
    elif args.action == "revert":
        revert(nft, table)
        print("partition_active=no")
    else:
        print(f"partition_active={'yes' if exists(nft, table) else 'no'}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, subprocess.CalledProcessError, OSError) as error:
        # Never print command arguments or Patroni response bodies: they can
        # contain internal addresses and infrastructure identifiers.
        safe_reasons = {
            "rollback timer is not active": "timer_inactive",
            "automatic rollback did not remove the smoke table": "smoke_table_remained",
            "rollback service did not succeed": "rollback_service_failed",
            "partition table remains after rollback": "partition_table_remained",
            "partition table was not installed": "partition_table_missing",
        }
        reason = safe_reasons.get(str(error), type(error).__name__)
        print(f"partition_guard_failed={reason}", file=sys.stderr)
        sys.exit(1)
