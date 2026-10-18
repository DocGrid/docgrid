#!/usr/bin/env python3
"""Audit upload acknowledgements before activating an isolated restored service.

Barman and GCS retain ownership of backup creation and transfer. This experiment
controller owns only its fresh evidence directory and explicitly supplied private
operator commands; it never discovers credentials or modifies public settings.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

from ha_evidence import json_text, replace_private, write_private

LABEL = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}\Z")
SHA = re.compile(r"[0-9a-f]{64}\Z")
WAL = re.compile(r"(?:[0-9A-F]{24}|[0-9A-F]{8}\.history)\Z")
IDS = ("documentId", "documentVersionId", "fileObjectId", "embeddingJobId")
CHECKS = ("backup_verified", "wal_chain_verified", "receiver_healthy",
          "identity_available_offsite", "restore_target_isolated", "rollback_ready",
          "public_config_preserved")
FUNCTIONS = ("download", "search", "job_resume", "new_upload", "new_indexing", "new_download")
# Match each Java enum independently: a Job status is not a DocumentVersion status.
SNAPSHOT_STATUSES = {
    "document_status": {"DRAFT", "UPLOADED", "INDEXING", "INDEXED", "FAILED", "ARCHIVED", "DELETED"},
    "version_status": {"UPLOADED", "PARSING", "CHUNKED", "EMBEDDING", "INDEXED", "FAILED"},
    "job_status": {"PENDING", "PROCESSING", "INDEXED", "FAILED", "CANCELED"},
}



class ContractError(ValueError):
    """Reject incomplete evidence without exposing private operator output."""


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def instant(value):
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if result.tzinfo is None:
            raise ValueError()
        return result
    except (AttributeError, TypeError, ValueError):
        raise ContractError("INVALID_TIMESTAMP") from None


def load(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise ContractError("INVALID_JSON") from None


def digest(value):
    return hashlib.sha256(json_text(value).encode()).hexdigest()


def positive(value):
    return type(value) is int and value > 0


def initialize(directory, run_id, revision):
    if not LABEL.fullmatch(run_id) or not re.fullmatch(r"[0-9a-f]{7,40}", revision):
        raise ContractError("INVALID_RUN_IDENTITY")
    directory.mkdir(mode=0o700, parents=False, exist_ok=False)
    write_private(directory / "manifest.json", json_text({"schema_version": 1,
        "run_id": run_id, "revision": revision, "created_at_utc": utc_now()}), exclusive=True)
    write_private(directory / "events.jsonl", "", exclusive=True)


def events(directory):
    manifest = load(directory / "manifest.json")
    if manifest.get("schema_version") != 1 or not LABEL.fullmatch(manifest.get("run_id", "")):
        raise ContractError("INVALID_MANIFEST")
    result, previous = [], "0" * 64
    try:
        lines = (directory / "events.jsonl").read_text().splitlines()
        for ordinal, line in enumerate(lines, 1):
            event = json.loads(line)
            checksum = event.pop("sha256")
            if (event.get("sequence") != ordinal or event.get("previous") != previous
                    or event.get("run_id") != manifest["run_id"] or digest(event) != checksum):
                raise ValueError()
            instant(event["at_utc"])
            event["sha256"] = checksum
            result.append(event)
            previous = checksum
    except (OSError, ValueError, KeyError, TypeError):
        raise ContractError("CORRUPT_EVENT_LEDGER") from None
    return result


def append(directory, kind, data):
    # One experiment controller serializes stages; flock also protects live response writers.
    import fcntl
    with (directory / "events.lock").open("a+") as lock:
        os.fchmod(lock.fileno(), 0o600)
        fcntl.flock(lock, fcntl.LOCK_EX)
        old = events(directory)
        if old and old[-1]["kind"] == "finished":
            raise ContractError("RUN_ALREADY_FINISHED")
        event = {"sequence": len(old) + 1, "run_id": load(directory / "manifest.json")["run_id"],
                 "previous": old[-1]["sha256"] if old else "0" * 64,
                 "kind": kind, "at_utc": utc_now(), "data": data}
        event["sha256"] = digest(event)
        with (directory / "events.jsonl").open("a", encoding="utf-8") as output:
            output.write(json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n")
            output.flush()
            os.fsync(output.fileno())
        return event


def record_sent(directory, record):
    required = {"request_id", "sha256", "size", "cohort"}
    if (set(record) != required or not LABEL.fullmatch(record.get("request_id", ""))
            or not SHA.fullmatch(record.get("sha256", "")) or not positive(record.get("size"))
            or record.get("cohort") not in ("functional", "loss")):
        raise ContractError("INVALID_REQUEST")
    old = events(directory)
    if any(e["kind"] == "cutoff" for e in old):
        raise ContractError("REQUEST_AFTER_CUTOFF")
    if any(e["kind"] == "sent" and e["data"]["request_id"] == record["request_id"] for e in old):
        raise ContractError("DUPLICATE_REQUEST")
    append(directory, "sent", record)


def record_response(directory, record):
    if set(record) - {"request_id", "http_status", "ids"} or "request_id" not in record:
        raise ContractError("INVALID_RESPONSE")
    old = events(directory)
    request_id = record["request_id"]
    if not any(e["kind"] == "sent" and e["data"]["request_id"] == request_id for e in old):
        raise ContractError("RESPONSE_WITHOUT_REQUEST")
    if any(e["kind"] == "response" and e["data"]["request_id"] == request_id for e in old):
        raise ContractError("DUPLICATE_RESPONSE")
    if any(e["kind"] == "initial_audit" for e in old):
        raise ContractError("RESPONSE_AFTER_AUDIT")
    status = record.get("http_status")
    if status is not None and (type(status) is not int or not 100 <= status <= 599):
        raise ContractError("INVALID_HTTP_STATUS")
    if status in (200, 201):
        if set(record.get("ids", {})) != set(IDS) or not all(positive(record["ids"][k]) for k in IDS):
            raise ContractError("SUCCESS_WITHOUT_IDS")
    elif record.get("ids"):
        raise ContractError("IDS_WITHOUT_SUCCESS")
    # HTTP errors remain observations. They never prove that a DB transaction rolled back.
    append(directory, "response", {**record, "outcome": "ACKNOWLEDGED" if status in (200, 201)
           else "UNKNOWN" if status is None else "HTTP_ERROR"})


def validate_inputs(value):
    if not isinstance(value, dict) or value.get("schema_version") != 1:
        raise ContractError("INVALID_INPUT_SCHEMA")
    if set(value) - {"schema_version", "captured_at_utc", "checks", "backup", "identity_sha256", "wals", "originals"}:
        raise ContractError("UNSAFE_INPUT_FIELDS")
    instant(value.get("captured_at_utc"))
    checks = value.get("checks", {})
    if not isinstance(checks, dict) or set(checks) - set(CHECKS):
        raise ContractError("UNSAFE_INPUT_FIELDS")
    missing = [name for name in CHECKS if checks.get(name) is not True]
    if missing:
        return {"ready": False, "reasons": missing}
    backup = value.get("backup", {})
    if not isinstance(backup, dict) or set(backup) != {"id", "sha256", "size", "generation"}:
        raise ContractError("UNSAFE_INPUT_FIELDS")
    if (not re.fullmatch(r"[0-9]{8}T[0-9]{6}", backup.get("id", ""))
            or not SHA.fullmatch(backup.get("sha256", "")) or not positive(backup.get("size"))
            or not str(backup.get("generation", "")).isdigit()):
        raise ContractError("INVALID_BACKUP_IDENTITY")
    names = set()
    if not value.get("wals"):
        raise ContractError("NO_ARCHIVED_WAL")
    for wal in value["wals"]:
        if not isinstance(wal, dict) or set(wal) != {"name", "sha256", "size", "generation"}:
            raise ContractError("UNSAFE_INPUT_FIELDS")
        name = wal.get("name", "")
        if (not WAL.fullmatch(name) or name in names or not SHA.fullmatch(wal.get("sha256", ""))
                or not positive(wal.get("size")) or not str(wal.get("generation", "")).isdigit()):
            raise ContractError("INVALID_WAL_OBJECT")
        names.add(name)
    if not SHA.fullmatch(value.get("identity_sha256", "")):
        raise ContractError("NO_OFFSITE_IDENTITY")
    original_ids = set()
    if not isinstance(value.get("originals"), list) or not value["originals"]:
        raise ContractError("NO_PINNED_ORIGINALS")
    for original in value["originals"]:
        if (not isinstance(original, dict) or set(original) != {"fileObjectId", "sha256", "size", "generation"}
                or not positive(original["fileObjectId"]) or original["fileObjectId"] in original_ids
                or not SHA.fullmatch(original["sha256"]) or not positive(original["size"])
                or not str(original["generation"]).isdigit()):
            raise ContractError("INVALID_ORIGINAL_OBJECT")
        original_ids.add(original["fileObjectId"])
    return {"ready": True, "reasons": [], "wal_objects": len(names)}


def freeze_inputs(directory, value):
    if any(e["kind"] == "cutoff" for e in events(directory)):
        raise ContractError("INPUTS_ALREADY_FROZEN")
    result = validate_inputs(value)
    if not result["ready"]:
        raise ContractError("PREFLIGHT_NO_GO")
    if instant(value["captured_at_utc"]) > datetime.now(timezone.utc):
        raise ContractError("FUTURE_INPUT_CAPTURE")
    write_private(directory / "inputs.json", json_text(value), exclusive=True)
    append(directory, "cutoff", {"inputs_sha256": digest(value), "wal_objects": result["wal_objects"]})


def requests(directory):
    sent, received, cutoff_seen, audit_seen = {}, {}, False, False
    for event in events(directory):
        cutoff_seen = cutoff_seen or event["kind"] == "cutoff"
        audit_seen = audit_seen or event["kind"] == "initial_audit"
        if event["kind"] == "sent":
            if cutoff_seen:
                raise ContractError("REQUEST_AFTER_CUTOFF")
            key = event["data"]["request_id"]
            if key in sent:
                raise ContractError("DUPLICATE_REQUEST_EVIDENCE")
            sent[key] = {**event["data"], "sent_at_utc": event["at_utc"]}
        if event["kind"] == "response":
            if audit_seen:
                raise ContractError("RESPONSE_AFTER_AUDIT")
            key = event["data"]["request_id"]
            if key not in sent or key in received:
                raise ContractError("INVALID_RESPONSE_EVIDENCE")
            received[key] = {**event["data"], "ack_at_utc": event["at_utc"]}
    return [{**record, **received.get(key, {"outcome": "UNKNOWN", "http_status": None})}
            for key, record in sent.items()]


def audit_records(records, snapshot, *, pristine=True, originals=None):
    """Compare original acknowledgements with immutable first-restoration observations."""
    if not isinstance(snapshot, dict):
        raise ContractError("INVALID_SNAPSHOT")
    if snapshot.get("source_access_used") is not False:
        raise ContractError("SNAPSHOT_SOURCE_DEPENDENCY")
    if pristine and any(snapshot.get(key) is not False for key in ("worker_started", "flyway_ran", "repair_ran", "source_access_used")):
        raise ContractError("INITIAL_SNAPSHOT_NOT_PRISTINE")
    allowed_snapshot = {"worker_started", "flyway_ran", "repair_ran", "source_access_used", "records", "consistency"}
    if set(snapshot) - allowed_snapshot:
        raise ContractError("UNSAFE_SNAPSHOT_FIELDS")
    for name in ("worker_started", "flyway_ran", "repair_ran", "source_access_used"):
        if type(snapshot.get(name)) is not bool:
            raise ContractError("INVALID_SNAPSHOT_FLAGS")
    counters = snapshot.get("consistency", {})
    allowed_counters = {"checked_documents", "incomplete_jobs", "duplicate_chunks", "duplicate_embeddings", "missing_embeddings"}
    if (not isinstance(counters, dict) or set(counters) - allowed_counters
            or any(type(v) is not int or v < 0 for v in counters.values())):
        raise ContractError("UNSAFE_SNAPSHOT_FIELDS")
    pinned = {row["fileObjectId"]: row for row in originals or []}
    rows = snapshot.get("records")
    if not isinstance(rows, list):
        raise ContractError("INVALID_SNAPSHOT")
    indexed = {}
    allowed_row = {"request_id", "state", "ids", "relations_match", "sha256", "size", "generation",
                   "document_status", "version_status", "job_status", "attempt_count", "chunk_count", "embedding_count"}
    for row in rows:
        if not isinstance(row, dict) or set(row) - allowed_row:
            raise ContractError("UNSAFE_SNAPSHOT_FIELDS")
        if ("ids" in row and (not isinstance(row["ids"], dict) or set(row["ids"]) != set(IDS)
                or not all(positive(v) for v in row["ids"].values()))):
            raise ContractError("INVALID_SNAPSHOT_IDS")
        if "relations_match" in row and type(row["relations_match"]) is not bool:
            raise ContractError("INVALID_SNAPSHOT_RELATIONS")
        if "size" in row and not positive(row["size"]):
            raise ContractError("INVALID_SNAPSHOT_SIZE")
        if "sha256" in row and (not isinstance(row["sha256"], str) or not SHA.fullmatch(row["sha256"])):
            raise ContractError("INVALID_SNAPSHOT_HASH")
        if "generation" in row and (not isinstance(row["generation"], (int, str)) or not str(row["generation"]).isdigit()):
            raise ContractError("MISSING_OBJECT_GENERATION")
        for key in ("attempt_count", "chunk_count", "embedding_count"):
            if key in row and (type(row[key]) is not int or row[key] < 0):
                raise ContractError("INVALID_SNAPSHOT_COUNTER")
        for key, statuses in SNAPSHOT_STATUSES.items():
            if key in row and row[key] not in statuses:
                raise ContractError("INVALID_SNAPSHOT_STATUS")
        key = row.get("request_id")
        if key in indexed or key not in {r["request_id"] for r in records}:
            raise ContractError("UNBOUND_OR_DUPLICATE_SNAPSHOT")
        indexed[key] = row
    output = []
    for request in records:
        row = indexed.get(request["request_id"])
        outcome = request["outcome"]
        if row is None:
            raise ContractError("MISSING_SNAPSHOT_OBSERVATION")
        state = row.get("state")
        if state not in ("DB_MISSING", "DB_PARTIAL", "OBJECT_MISSING", "HASH_MISMATCH", "RESTORED"):
            raise ContractError("INVALID_RESTORED_STATE")
        if state == "RESTORED":
            observed_ids = row.get("ids", {})
            if set(observed_ids) != set(IDS) or not all(positive(observed_ids[k]) for k in IDS):
                raise ContractError("INCOMPLETE_RESTORED_IDS")
            if outcome == "ACKNOWLEDGED" and observed_ids != request["ids"]:
                raise ContractError("RESPONSE_BINDING_MISMATCH")
            if row.get("relations_match") is not True:
                raise ContractError("BROKEN_DOCUMENT_RELATIONS")
            if row.get("sha256") != request["sha256"] or row.get("size") != request["size"]:
                state = "HASH_MISMATCH"
            if not str(row.get("generation", "")).isdigit():
                raise ContractError("MISSING_OBJECT_GENERATION")
            # Pin the preserved object's generation; an unrelated live object cannot satisfy restoration.
            if originals is not None:
                original = pinned.get(observed_ids["fileObjectId"])
                if not original or any(str(row[k]) != str(original[k]) for k in ("sha256", "size", "generation")):
                    raise ContractError("RESTORED_OBJECT_NOT_PINNED")
        output.append({"request_id": request["request_id"], "cohort": request["cohort"],
                       "http_outcome": outcome, "restored_state": state,
                       "ack_at_utc": request.get("ack_at_utc")})
    acknowledged = [r for r in output if r["http_outcome"] == "ACKNOWLEDGED"]
    missing = [r for r in acknowledged if r["restored_state"] != "RESTORED"]
    activation_allowed = (bool(records) and any(r["cohort"] == "functional" for r in output)
        and all(r["restored_state"] == "RESTORED" for r in output if r["cohort"] == "functional")
        and not any(r["restored_state"] in ("DB_PARTIAL", "OBJECT_MISSING", "HASH_MISMATCH") for r in output))
    return {"acknowledged": len(acknowledged), "restored_acknowledged": len(acknowledged) - len(missing),
            "lost_or_incomplete_acknowledged": len(missing),
            "unknown_http": sum(r["http_outcome"] == "UNKNOWN" for r in output),
            "http_error": sum(r["http_outcome"] == "HTTP_ERROR" for r in output),
            "activation_allowed": activation_allowed, "requests": output}


def initial_audit(directory, snapshot):
    old = events(directory)
    if not any(e["kind"] == "restored" for e in old) or any(e["kind"] == "initial_audit" for e in old):
        raise ContractError("INVALID_AUDIT_ORDER")
    result = audit_records(requests(directory), snapshot, originals=load(directory / "inputs.json")["originals"])
    write_private(directory / "initial-snapshot.json", json_text(snapshot), exclusive=True)
    write_private(directory / "initial-audit.json", json_text(result), exclusive=True)
    append(directory, "initial_audit", {"snapshot_sha256": digest(snapshot), "audit_sha256": digest(result),
           "activation_allowed": result["activation_allowed"], "acknowledged": result["acknowledged"],
           "lost_or_incomplete_acknowledged": result["lost_or_incomplete_acknowledged"]})
    return result


def execute_operator(plan, name):
    action = plan.get(name, {})
    command = action.get("argv")
    timeout = action.get("timeout_seconds", 300)
    if not isinstance(command, list) or not command or not all(isinstance(x, str) for x in command):
        raise ContractError("INVALID_OPERATOR_COMMAND")
    if type(timeout) is not int or not 1 <= timeout <= 1800:
        raise ContractError("INVALID_OPERATOR_TIMEOUT")
    # Capture output only in memory: private SSH/CLI errors must never enter evidence.
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        raise ContractError("OPERATOR_" + name.upper() + "_UNAVAILABLE") from None
    if result.returncode:
        raise ContractError("OPERATOR_" + name.upper() + "_FAILED")
    try:
        value = json.loads(result.stdout)
        if not isinstance(value, dict):
            raise ValueError()
        return value
    except ValueError:
        raise ContractError("OPERATOR_" + name.upper() + "_INVALID_RECEIPT") from None


def execute(directory, plan):
    """Prevent two controllers from restoring or activating the same run concurrently."""
    import fcntl
    with (directory / "execute.lock").open("a+") as lock:
        os.fchmod(lock.fileno(), 0o600)
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ContractError("RUN_ALREADY_LOCKED") from None
        return _execute(directory, plan)


def _execute(directory, plan):
    old = events(directory)
    cutoffs = [e for e in old if e["kind"] == "cutoff"]
    if len(cutoffs) != 1 or any(e["kind"] == "restore_started" for e in old):
        raise ContractError("RUN_NOT_READY_OR_ALREADY_STARTED")
    if digest(load(directory / "inputs.json")) != cutoffs[0]["data"]["inputs_sha256"]:
        raise ContractError("FROZEN_INPUTS_CHANGED")
    # 1. Validate every operator and cleanup command before any remote action.
    if set(plan) != {"restore", "snapshot", "activate", "functional", "final_snapshot", "cleanup"}:
        raise ContractError("INCOMPLETE_OPERATOR_PLAN")
    for action in plan.values():
        if (not isinstance(action, dict) or not isinstance(action.get("argv"), list)
                or not action["argv"] or not all(isinstance(x, str) for x in action["argv"])
                or type(action.get("timeout_seconds", 300)) is not int
                or not 1 <= action.get("timeout_seconds", 300) <= 1800):
            raise ContractError("INVALID_OPERATOR_COMMAND")
    t0, failure, functional, cleanup, audit = cutoffs[0]["at_utc"], None, {}, {}, None
    final_audit = None
    append(directory, "restore_started", {})
    started = time.monotonic()
    try:
        # 2. Restore only the frozen inputs; a receipt binds their digest.
        receipt = execute_operator(plan, "restore")
        if (receipt.get("restored") is not True or receipt.get("source_access_used") is not False
                or receipt.get("inputs_sha256") != cutoffs[0]["data"]["inputs_sha256"]):
            raise ContractError("RESTORE_SOURCE_DEPENDENCY")
        append(directory, "restored", {"elapsed_seconds": round(time.monotonic() - started, 3)})
        # 3. Persist the pristine DB/GCS audit before any application mutation.
        audit = initial_audit(directory, execute_operator(plan, "snapshot"))
        if not audit["activation_allowed"]:
            raise ContractError("INITIAL_AUDIT_BLOCKS_ACTIVATION")
        # 4. Only a durable, passing initial gate permits bucket rebinding and Worker activation.
        if execute_operator(plan, "activate").get("activated") is not True:
            raise ContractError("ACTIVATION_FAILED")
        activated = append(directory, "activated", {})
        # 5. Keep each actual feature observation and its recovery time separate.
        observations = execute_operator(plan, "functional")
        if set(observations) != set(FUNCTIONS):
            raise ContractError("MISSING_FUNCTIONAL_CHECK")
        for name in FUNCTIONS:
            item = observations[name]
            if (not isinstance(item, dict) or set(item) != {"passed", "samples", "at_utc"}
                    or item.get("passed") is not True or not positive(item.get("samples"))):
                raise ContractError("FUNCTIONAL_" + name.upper() + "_FAILED")
            at = instant(item.get("at_utc"))
            if not instant(activated["at_utc"]) <= at <= datetime.now(timezone.utc):
                raise ContractError("INVALID_FUNCTIONAL_TIMESTAMP")
            functional[name] = {"passed": True, "samples": item["samples"], "at_utc": at.isoformat(),
                                "from_cutoff_seconds": round((at - instant(t0)).total_seconds(), 3)}
            append(directory, "functional", {"function": name, "samples": item["samples"],
                   "recovered_at_utc": item["at_utc"],
                   "from_cutoff_seconds": round((at - instant(t0)).total_seconds(), 3)})
        # 6. Final convergence cannot replace or overwrite the initial loss audit.
        final_snapshot = execute_operator(plan, "final_snapshot")
        counters = final_snapshot.get("consistency", {})
        if (set(counters) != {"checked_documents", "incomplete_jobs", "duplicate_chunks", "duplicate_embeddings", "missing_embeddings"}
                or not positive(counters.get("checked_documents")) or any(
                type(counters.get(k)) is not int or counters[k] != 0
                for k in ("incomplete_jobs", "duplicate_chunks", "duplicate_embeddings", "missing_embeddings"))):
            raise ContractError("FINAL_CONSISTENCY_FAILED")
        final_audit = audit_records(requests(directory), final_snapshot, pristine=False,
                                    originals=load(directory / "inputs.json")["originals"])
        write_private(directory / "final-snapshot.json", json_text(final_snapshot), exclusive=True)
        write_private(directory / "final-audit.json", json_text(final_audit), exclusive=True)
        append(directory, "final_audit", {"snapshot_sha256": digest(final_snapshot),
               "audit_sha256": digest(final_audit), "consistency": counters})
    except (ContractError, OSError, KeyError, TypeError, AttributeError, KeyboardInterrupt) as error:
        failure = str(error) if isinstance(error, ContractError) else ("RUN_INTERRUPTED" if isinstance(error, KeyboardInterrupt) else "INVALID_STAGE_EVIDENCE")
        append(directory, "interrupted", {"reason": failure})
    finally:
        # 7. Always attempt owned-resource cleanup; missing confirmation prevents PASS.
        try:
            cleanup = execute_operator(plan, "cleanup")
            if any(cleanup.get(k) is not True for k in
                   ("public_config_preserved", "source_cluster_restored", "owned_resources_removed")):
                raise ContractError("CLEANUP_NOT_CONFIRMED")
            append(directory, "cleanup", {k: cleanup[k] for k in
                   ("public_config_preserved", "source_cluster_restored", "owned_resources_removed")})
        except ContractError as error:
            failure = failure or str(error)
            append(directory, "cleanup_unconfirmed", {"reason": str(error)})
    summary = {"run_id": load(directory / "manifest.json")["run_id"],
               "execution": "PASS" if failure is None else "INCOMPLETE",
               "reason": failure, "rpo_guarantee": "NOT_EVALUATED",
               "scope": "isolated_document_download_search_indexing",
               "initial_audit": audit, "final_audit": final_audit,
               "functional_checks": functional}
    replace_private(directory / "summary.json", json_text(summary))
    append(directory, "finished", {"execution": summary["execution"], "summary_sha256": digest(summary)})
    return summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["init", "sent", "response", "preflight", "freeze", "execute"])
    parser.add_argument("--run-dir", type=Path)
    parser.add_argument("--run-id")
    parser.add_argument("--revision")
    parser.add_argument("--input", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.action == "init":
            initialize(args.run_dir, args.run_id, args.revision)
            result = {"initialized": True}
        elif args.action == "preflight":
            result = validate_inputs(load(args.input))
        elif args.action == "sent":
            record_sent(args.run_dir, load(args.input)); result = {"recorded": True}
        elif args.action == "response":
            record_response(args.run_dir, load(args.input)); result = {"recorded": True}
        elif args.action == "freeze":
            freeze_inputs(args.run_dir, load(args.input)); result = {"frozen": True}
        else:
            summary = execute(args.run_dir, load(args.input))
            result = {"execution": summary["execution"], "reason": summary["reason"]}
        print(json.dumps(result, ensure_ascii=False))
        return 0 if result.get("ready", True) and result.get("execution") != "INCOMPLETE" else 1
    except (ContractError, OSError, TypeError) as error:
        print(json.dumps({"result": "NO_GO", "reason": str(error) if isinstance(error, ContractError)
                         else "LOCAL_INPUT_OR_EVIDENCE_ERROR"}))
        return 1


if __name__ == "__main__":
    sys.exit(main())
