#!/usr/bin/env python3
"""Preserve and recreate one DocGrid OpenSQL node with Docker's process-reaping init.

This host-side tool never stops, renames, removes, or bootstraps a DB container.
Capture preserves installed packages from the old container's writable layer in a
local-only image. An operator must separately stop the old replica and bootstrap.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import socket
import subprocess
import sys
from pathlib import Path


NODE_LABELS = {"node1", "node2", "node3"}
BASE_IMAGE = ("rockylinux/rockylinux:9.7@sha256:"
              "53f4c6dcb34e1403bd93207351f0af9a593610faeb7165cb8a037346765199b0")
MEMORY_BYTES = 6 * 1024 ** 3
MEMORY_SWAP_BYTES = 12 * 1024 ** 3
NANO_CPUS = 2 * 10 ** 9


def node_label(node: str) -> str:
    """Accept a private runtime hostname but publish only its node ordinal."""
    if not re.fullmatch(r"[a-z][a-z0-9-]*-node[123]", node):
        raise ValueError("Expected a valid DB node hostname ending in node1, node2, or node3")
    label = node.rsplit("-", 1)[-1]
    if label not in NODE_LABELS:
        raise ValueError("Only the three DB node labels are allowed")
    return label


def data_source(node: str) -> str:
    """Return the pre-existing bind directory for the validated VM/container."""
    node_label(node)
    return f"/srv/{node}"


def captured_image(node: str) -> str:
    """Keep each VM's installed packages in a distinct, never-pushed local image."""
    return f"docgrid/opensql-{node_label(node)}:issue-401-preinit"


def create_command(node: str) -> list[str]:
    """Preserve the installed image, hostname, resources, and persistent mount."""
    return [
        "docker", "run", "-d", "--init", "--name", node, "--hostname", node,
        "--network", "host", "--restart", "unless-stopped", "--cpus", "2",
        "--memory", "6g", "--memory-swap", "12g", "--mount",
        f"type=bind,source={data_source(node)},target=/var/lib/docgrid",
        captured_image(node), "sleep", "infinity",
    ]


def configuration_errors(node: str, container: dict, image: dict, pid_one: str) -> list[str]:
    """Return field names only; never export environment values or mount sources."""
    config = container.get("Config") or {}
    host = container.get("HostConfig") or {}
    mounts = container.get("Mounts") or []
    expected_mount = {"Type": "bind", "Source": data_source(node),
                      "Destination": "/var/lib/docgrid", "RW": True}
    checks = {
        "running": (container.get("State") or {}).get("Running") is True,
        "image": container.get("Image") == image.get("Id"),
        "hostname": config.get("Hostname") == node,
        "command": config.get("Cmd") == ["sleep", "infinity"],
        "init": host.get("Init") is True,
        "pid1": pid_one.strip() in {"docker-init", "tini"},
        "network": host.get("NetworkMode") == "host",
        "restart": (host.get("RestartPolicy") or {}).get("Name") == "unless-stopped",
        "cpu": host.get("NanoCpus") == NANO_CPUS,
        "memory": host.get("Memory") == MEMORY_BYTES,
        "memory_swap": host.get("MemorySwap") == MEMORY_SWAP_BYTES,
        "mount": len(mounts) == 1 and all(
            mounts[0].get(key) == value for key, value in expected_mount.items()),
        "environment": config.get("Env") == image.get("Config", {}).get("Env"),
        "labels": config.get("Labels") == image.get("Config", {}).get("Labels"),
    }
    return [field for field, valid in checks.items() if not valid]


def docker_json(*args: str) -> dict:
    """Read exactly one Docker inspection object without logging its raw JSON."""
    result = subprocess.run(["docker", *args], check=True, capture_output=True, text=True)
    objects = json.loads(result.stdout)
    if not isinstance(objects, list) or len(objects) != 1:
        raise RuntimeError("Unexpected Docker inspect response")
    return objects[0]


def verify(node: str) -> None:
    """Fail closed unless the new container has the exact observed runtime contract."""
    container = docker_json("container", "inspect", node)
    image = docker_json("image", "inspect", captured_image(node))
    pid_one = subprocess.run(
        ["docker", "exec", node, "ps", "-p", "1", "-o", "comm="],
        check=True, capture_output=True, text=True,
    ).stdout
    errors = configuration_errors(node, container, image, pid_one)
    if errors:
        raise RuntimeError("Container contract mismatch: " + ",".join(errors))
    print(f"node={node} init=true pid1=reaping-init runtime_contract=pass")


def capture(node: str) -> None:
    """Save the old container's installed OS layer locally without its bind-mounted data."""
    # 1. Require the original live container and its exact pre-change contract.
    if socket.gethostname().split(".")[0] != node:
        raise RuntimeError("Node name does not match this VM")
    old = docker_json("container", "inspect", node)
    base = docker_json("image", "inspect", BASE_IMAGE)
    old_pid_one = subprocess.run(
        ["docker", "exec", node, "sh", "-c", "read -r p </proc/1/comm; printf %s \"$p\""],
        check=True, capture_output=True, text=True,
    ).stdout
    errors = configuration_errors(node, old, base, old_pid_one)
    if old.get("Config", {}).get("Image") != BASE_IMAGE:
        errors.append("source_image")
    if old_pid_one.strip() != "sleep":
        errors.append("old_pid1")
    errors = [error for error in errors if error not in {"image", "init", "pid1"}]
    if errors:
        raise RuntimeError("Old container contract mismatch: " + ",".join(errors))

    # 2. Never overwrite a previous capture or publish it to a registry.
    tag = captured_image(node)
    existing = subprocess.run(["docker", "image", "inspect", tag],
                              capture_output=True, check=False)
    if existing.returncode == 0:
        raise RuntimeError("Captured image already exists; refusing to overwrite it")
    subprocess.run(["docker", "commit", "--pause=false", node, tag], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    image = docker_json("image", "inspect", tag)
    if image.get("Config", {}).get("Env") != base.get("Config", {}).get("Env"):
        raise RuntimeError("Captured image environment differs from original")
    print(f"node={node} local_image_captured=true old_container_unchanged=true")


def create(node: str) -> None:
    """Create only an absent container; never touch the old data or start DB services."""
    # 1. Refuse the wrong host or an incomplete persistent installation.
    if socket.gethostname().split(".")[0] != node:
        raise RuntimeError("Node name does not match this VM")
    source = Path(data_source(node))
    if not (source / "opensql/etc/patroni/patroni.yml").is_file():
        raise RuntimeError("Persistent Patroni installation is missing")

    # 2. Do not replace an existing container or mount over another running one.
    existing = subprocess.run(["docker", "container", "inspect", node],
                              capture_output=True, check=False)
    if existing.returncode == 0:
        raise RuntimeError("Target container already exists; stop and rename it first")
    running = subprocess.run(["docker", "ps", "-q"], check=True,
                             capture_output=True, text=True).stdout.split()
    for container_id in running:
        other = docker_json("container", "inspect", container_id)
        if any(mount.get("Source") == str(source) for mount in other.get("Mounts") or []):
            raise RuntimeError("Persistent data is mounted by a running container")

    # 3. Use the captured OS layer, never the bare Rocky image that lacks packages.
    docker_json("image", "inspect", captured_image(node))
    subprocess.run(create_command(node), check=True, stdout=subprocess.DEVNULL,
                   stderr=subprocess.PIPE)
    verify(node)
    print("db_services_started=false; run the existing host bootstrap separately")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("capture", "create", "verify"))
    parser.add_argument("node", help="the actual local VM/container hostname")
    args = parser.parse_args()
    try:
        node_label(args.node)
    except ValueError as error:
        parser.error(str(error))
    if os.geteuid() != 0:
        parser.error("Run as root on the DB VM host")
    try:
        if args.action == "capture":
            capture(args.node)
        elif args.action == "create":
            create(args.node)
        else:
            verify(args.node)
    except subprocess.CalledProcessError as error:
        # Docker stderr may include infrastructure details; report only the status.
        print(f"HA container {args.action} failed: Docker exit status {error.returncode}",
              file=sys.stderr)
        return 1
    except (OSError, ValueError, RuntimeError) as error:
        print(f"HA container {args.action} failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
