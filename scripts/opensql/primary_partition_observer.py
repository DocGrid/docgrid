#!/usr/bin/env python3
"""Sample local HA control state during one bounded Primary partition run.

The observer records only role, state, local health and safe node labels. It
does not infer SQL writability from a Patroni role; the HTTP/DB ledger and
OpenProxy read-write probes provide the separate data-plane evidence.
"""

import argparse
import csv
import datetime
import json
import re
import sys
import time
import urllib.request


def read_json(port, path):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/{path}", timeout=0.8) as response:
            return json.load(response)
    except (OSError, ValueError):
        return None


def sample(node):
    patroni = read_json(8008, "patroni")
    etcd = read_json(2379, "health")
    role = patroni.get("role", "unknown") if patroni else "unknown"
    state = patroni.get("state", "unknown") if patroni else "unknown"
    # Never print unexpected server text into evidence.
    if role not in ("primary", "replica", "sync_standby", "quorum_standby", "unknown"):
        role = "other"
    if state not in ("running", "stopped", "unknown"):
        state = "other"
    health = str(etcd.get("health", "unknown")).lower() if etcd else "unknown"
    if health not in ("true", "false"):
        health = "unknown"
    return (datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds"),
            node, role, state, health)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("node", choices=("db1", "db2", "db3"))
    parser.add_argument("run_id")
    parser.add_argument("--seconds", type=int, default=200)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z][a-z0-9]{2,15}", args.run_id) or not 120 <= args.seconds <= 600:
        parser.error("invalid bounded run")
    writer = csv.writer(sys.stdout)
    writer.writerow(("at_utc", "node", "patroni_role", "patroni_state", "local_etcd_health"))
    deadline = time.monotonic() + args.seconds
    while time.monotonic() < deadline:
        start = time.monotonic()
        writer.writerow(sample(args.node))
        sys.stdout.flush()
        time.sleep(max(0, 1 - (time.monotonic() - start)))


if __name__ == "__main__":
    main()
