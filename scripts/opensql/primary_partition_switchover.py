#!/usr/bin/env python3
"""Move a healthy Primary to the OpenProxy-hosting test node before isolation."""

import ipaddress
import json
import sys
import urllib.error
import urllib.request


def read(path):
    with urllib.request.urlopen(f"http://127.0.0.1:8008/{path}", timeout=5) as response:
        return json.load(response)


def main():
    # 1. Verify the live local role and three-member cluster before choosing a target.
    local = read("patroni")
    cluster = read("cluster")
    members = cluster.get("members", [])
    # The controller sends the intended VM's private IP on stdin. It is never
    # placed in the command line or printed to the evidence log.
    target_host = sys.stdin.readline().strip()
    target_address = ipaddress.ip_address(target_host)
    if not isinstance(target_address, ipaddress.IPv4Address) or not target_address.is_private:
        raise RuntimeError("target must be a private IPv4 address")
    leader = [member for member in members if member.get("role") == "leader"]
    candidate = [member for member in members if member.get("host") == str(target_address)]
    checks = {
        "local_primary": local.get("role") == "primary",
        "three_members": len(members) == 3,
        "one_leader": len(leader) == 1,
        "leader_matches_local": len(leader) == 1 and leader[0].get("name") == local.get("patroni", {}).get("name"),
        "one_target": len(candidate) == 1,
        "target_healthy_replica": len(candidate) == 1 and candidate[0].get("role") == "replica"
            and candidate[0].get("state") == "streaming",
    }
    if len(sys.argv) == 2 and sys.argv[1] == "check":
        print(" ".join(f"{key}={str(value).lower()}" for key, value in checks.items()))
        if len(candidate) == 1:
            role = candidate[0].get("role")
            state = candidate[0].get("state")
            print(f"target_role={role if role in ('replica', 'leader') else 'other'} "
                  f"target_state={state if state in ('running', 'streaming') else 'other'}")
        return
    if len(sys.argv) != 1 or not all(checks.values()):
        raise RuntimeError("switchover precondition failed")
    # 2. Use Patroni's planned switchover endpoint, not forced failover.
    body = json.dumps({"leader": leader[0]["name"], "candidate": candidate[0]["name"]}).encode()
    request = urllib.request.Request(
        "http://127.0.0.1:8008/switchover", data=body,
        headers={"Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            status = response.status
    except urllib.error.HTTPError as error:
        status = error.code
    print(f"switchover_http_status={status}")
    if status != 200:
        raise RuntimeError("switchover was not confirmed")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError) as error:
        # Only fixed status labels leave the VM; never print Patroni host data.
        print(f"switchover_failed={type(error).__name__}", file=sys.stderr)
        sys.exit(1)
