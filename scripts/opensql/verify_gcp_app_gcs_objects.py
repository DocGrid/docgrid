#!/usr/bin/env python3
"""Compare synthetic PDF records in OpenSQL with actual GCS object bytes.

Only a synthetic file hash, counts, states, and sizes reach stdout. Database
locations, bucket names, object keys, and command errors stay in memory.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import shlex
import subprocess
from datetime import datetime
from zoneinfo import ZoneInfo


KST = ZoneInfo("Asia/Seoul")


class VerificationFailure(Exception):
    """Represent a safe failure category without an underlying resource name."""


def run(*command: str) -> bytes:
    result = subprocess.run(command, capture_output=True, check=False)
    if result.returncode:
        raise VerificationFailure(f"{command[0]}:EXIT_{result.returncode}")
    return result.stdout


def log(stage: str, **values: object) -> None:
    timestamp = datetime.now(KST).isoformat(timespec="milliseconds")
    detail = " ".join(f"{key}={value}" for key, value in values.items())
    print(f"{timestamp} 단계={stage} {detail}", flush=True)


def fetch_rows(node: str, zone: str, ssh_key: str, digests: list[str],
               credential_env: str, psql_bin: str) -> list[list[str]]:
    quoted = ",".join(f"'{digest}'" for digest in digests)
    sql = (
        "SELECT f.file_hash, f.file_size, f.storage_provider, f.bucket_name, "
        "f.object_key, d.status, v.status "
        "FROM file_objects f JOIN document_versions v ON v.file_object_id = f.id "
        "JOIN documents d ON d.id = v.document_id "
        f"WHERE f.file_hash IN ({quoted}) ORDER BY f.file_hash;"
    )
    encoded = base64.b64encode(sql.encode()).decode()
    psql = (
        f'. {shlex.quote(credential_env)}; '
        'export PGPASSWORD="$PG_SUPERUSER_PASSWORD"; '
        f'exec {shlex.quote(psql_bin)} -h 127.0.0.1 -U postgres '
        '-d docgrid -X -v ON_ERROR_STOP=1 -At -F "|" -f -'
    )
    remote = (
        'container=$(sudo docker ps --format "{{.ID}}" | head -1); '
        f'printf %s {shlex.quote(encoded)} | base64 -d | '
        f'sudo docker exec -i --user root "$container" sh -c {shlex.quote(psql)}'
    )
    output = run(
        "gcloud", "compute", "ssh", node, f"--zone={zone}",
        "--tunnel-through-iap", f"--ssh-key-file={ssh_key}",
        f"--command={remote}",
    ).decode()
    return [line.split("|") for line in output.splitlines() if line]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--node", required=True)
    parser.add_argument("--zone", required=True)
    parser.add_argument("--ssh-key", required=True)
    parser.add_argument("--credential-env", required=True)
    parser.add_argument("--psql-bin", required=True)
    parser.add_argument("--sha256", action="append", required=True)
    args = parser.parse_args()
    digests = args.sha256
    if len(set(digests)) != len(digests) or any(
        len(value) != 64 or any(character not in "0123456789abcdef" for character in value)
        for value in digests
    ):
        raise VerificationFailure("INVALID_DIGEST_LIST")
    log("시작", run_id=args.run_id, expected_files=len(digests))
    rows = fetch_rows(args.node, args.zone, args.ssh_key, digests,
                      args.credential_env, args.psql_bin)
    if len(rows) != len(digests):
        raise VerificationFailure("DB_ROW_COUNT_MISMATCH")
    seen: set[str] = set()
    for row in rows:
        if len(row) != 7:
            raise VerificationFailure("DB_ROW_SHAPE_MISMATCH")
        digest, file_size, provider, bucket, key, document_status, version_status = row
        if digest not in digests or digest in seen or provider != "GCS":
            raise VerificationFailure("DB_FILE_RECORD_MISMATCH")
        seen.add(digest)
        uri = f"gs://{bucket}/{key}"
        object_bytes = run("gcloud", "storage", "cat", uri)
        if len(object_bytes) != int(file_size) or hashlib.sha256(object_bytes).hexdigest() != digest:
            raise VerificationFailure("GCS_OBJECT_MISMATCH")
        log("DB-GCS대조", result="통과", file_hash_prefix=digest[:12],
            db_rows=1, gcs_objects=1, bytes=len(object_bytes),
            document_status=document_status, version_status=version_status)
    log("종료", result="통과", db_rows=len(rows), gcs_objects=len(rows),
        hash_matches=len(rows))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except VerificationFailure as error:
        log("종료", result="실패", category=str(error))
        raise SystemExit(1) from None
