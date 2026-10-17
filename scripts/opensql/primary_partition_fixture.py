#!/usr/bin/env python3
"""Create one short-lived synthetic ADMIN for the isolated HA HTTP probe.

The existing root-only OpenProxy config supplies the app DB password inside the
container. No DB password is copied to the host, command line, or evidence log.
"""

import argparse
import re
import subprocess
import sys


INNER_QUERY = r'''
import os
import re
import subprocess
import sys

config = open("/var/lib/docgrid/opensql/etc/openproxy/openproxy.toml", encoding="utf-8").read()
section = re.search(r"(?ms)^\[pools\.docgrid\.users\.0\]\s*$(.*?)(?=^\[|\Z)", config)
password = re.search(r'(?m)^password\s*=\s*"([0-9a-fA-F]{64})"\s*$', section.group(1)) if section else None
if not password:
    sys.exit(2)
env = os.environ.copy()
env["PGPASSWORD"] = password.group(1)
port = os.environ.get("DOCGRID_PROBE_PORT", "5432")
if port not in ("5432", "6432"):
    sys.exit(2)
result = subprocess.run(
    ["/var/lib/docgrid/opensql/bin/psql", "-h", "127.0.0.1", "-p", port,
     "-U", "docgrid_app", "-d", "docgrid", "-X", "-q", "-At",
     "-v", "ON_ERROR_STOP=1", "-f", "-"],
    input=sys.stdin.read(), text=True, capture_output=True, env=env,
)
if result.returncode:
    sys.exit(3)
sys.stdout.write(result.stdout)
'''


def query(container, sql, port="5432"):
    result = subprocess.run(
        ["docker", "exec", "-i", "-e", f"DOCGRID_PROBE_PORT={port}",
         container, "python3", "-c", INNER_QUERY],
        input=sql, text=True, capture_output=True,
    )
    if result.returncode:
        raise RuntimeError(f"db_query_failed_{result.returncode}")
    return result.stdout.strip()


def primary(container):
    if query(container, "SELECT pg_is_in_recovery();\n") != "f":
        raise RuntimeError("local_database_is_not_primary")


def fixture_count(container, email, port="5432"):
    sql = f"SELECT count(*) FROM users WHERE email = '{email}';\n"
    if port == "6432":
        sql = f"BEGIN READ WRITE; {sql} COMMIT;\n"
    result = query(container, sql, port=port)
    count = next((line for line in result.splitlines() if line.isdigit()), "")
    if not count.isdigit():
        raise RuntimeError("fixture_count_invalid")
    return int(count)


def timer_active(unit):
    result = subprocess.run(["systemctl", "is-active", f"{unit}.timer"],
                            text=True, capture_output=True)
    return result.stdout.strip() == "active"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("role", "proxy-role", "arm", "create", "status", "audit", "remove", "cancel"))
    parser.add_argument("container")
    parser.add_argument("run_id")
    args = parser.parse_args()
    if not re.fullmatch(r"docgrid-node[123]", args.container):
        raise ValueError("container_not_allowlisted")
    if not re.fullmatch(r"[a-z][a-z0-9]{2,19}", args.run_id):
        raise ValueError("run_id_invalid")
    email = f"ha-{args.run_id}-admin@invalid.example"
    unit = f"docgrid-partition-fixture-{args.run_id}"

    if args.action == "role":
        print("primary" if query(args.container, "SELECT pg_is_in_recovery();\n") == "f" else "replica")
    elif args.action == "proxy-role":
        # A read-only SELECT may intentionally route to a replica. Probe the
        # write path with an explicit read-write transaction instead.
        result = query(args.container, "BEGIN READ WRITE; SELECT pg_is_in_recovery(); COMMIT;\n", port="6432")
        print(f"proxy_target_writable={'yes' if 'f' in result.splitlines() else 'no'}")
    elif args.action == "arm":
        # 1. Arm a repeating, independent cleanup attempt on every DB VM.
        if timer_active(unit) or fixture_count(args.container, email, port="6432"):
            raise RuntimeError("fixture_or_timer_already_exists")
        subprocess.run(
            ["systemd-run", "--quiet", "--collect", "--unit", unit,
             "--on-active=1800s", "--on-unit-active=60s",
             "/usr/local/sbin/docgrid-partition-fixture", "remove", args.container, args.run_id],
            check=True, capture_output=True,
        )
        if not timer_active(unit):
            raise RuntimeError("fixture_timer_inactive")
        print("fixture_cleanup_armed=yes")
    elif args.action == "create":
        # 2. Only the Primary may insert a disabled-login synthetic ADMIN.
        primary(args.container)
        if not timer_active(unit) or fixture_count(args.container, email, port="6432"):
            raise RuntimeError("fixture_creation_guard_failed")
        sql = f"""
BEGIN;
INSERT INTO users (email, password_hash, name, status)
VALUES ('{email}', '!disabled!', 'HA partition admin', 'ACTIVE');
INSERT INTO user_roles (user_id, role_id, assigned_by, assigned_at)
SELECT u.id, r.id, u.id, CURRENT_TIMESTAMP
FROM users u CROSS JOIN roles r
WHERE u.email = '{email}' AND r.code = 'ADMIN';
COMMIT;
SELECT u.id FROM users u JOIN user_roles ur ON ur.user_id = u.id
JOIN roles r ON r.id = ur.role_id WHERE u.email = '{email}' AND r.code = 'ADMIN';
"""
        user_id = query(args.container, sql)
        if not user_id.isdigit():
            raise RuntimeError("fixture_user_id_missing")
        print(f"fixture_user_id={user_id}")
    elif args.action == "status":
        print(f"fixture_count={fixture_count(args.container, email, port='6432')} "
              f"cleanup_armed={'yes' if timer_active(unit) else 'no'}")
    elif args.action == "audit":
        # The explicit write transaction asks OpenProxy for the current
        # Primary. Return only synthetic request IDs and row multiplicities.
        result = query(args.container, f"""
BEGIN READ WRITE;
SELECT request_id || ',' || count(*) FROM ha_probe_writes
WHERE run_id = '{args.run_id}' GROUP BY request_id ORDER BY request_id;
COMMIT;
""", port="6432")
        for line in result.splitlines():
            if re.fullmatch(rf"{args.run_id}-[A-Za-z0-9._-]+,[0-9]+", line):
                print(line)
    elif args.action == "remove":
        # 3. Route cleanup through OpenProxy to whichever DB is Primary.
        query(args.container, f"""
BEGIN READ WRITE;
DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE email = '{email}');
DELETE FROM users WHERE email = '{email}';
COMMIT;
""", port="6432")
        print("fixture_removed=yes")
    else:
        # 4. Never cancel automatic cleanup while a fixture still exists.
        if fixture_count(args.container, email, port="6432"):
            raise RuntimeError("fixture_still_exists")
        subprocess.run(["systemctl", "stop", f"{unit}.timer"], check=False,
                       capture_output=True)
        print("fixture_cleanup_cancelled=yes")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        reason = str(error) if isinstance(error, RuntimeError) and str(error).startswith("db_query_failed_") else type(error).__name__
        print(f"fixture_failed={reason}", file=sys.stderr)
        sys.exit(1)
