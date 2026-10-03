"""Exercise the OpenSQL node runtime contract without touching GCP or Docker."""

from __future__ import annotations

import importlib.util
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("ha_node_container.py")
SPEC = importlib.util.spec_from_file_location("ha_node_container", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("HA node container module cannot be loaded")
RUNTIME = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNTIME)
NODE = "example-node1"


def valid_container() -> dict:
    """Model the exact three-node Docker configuration after --init migration."""
    return {
        "State": {"Running": True},
        "Image": "sha256:example-image-id",
        "Config": {"Image": RUNTIME.captured_image(NODE), "Hostname": NODE,
                   "Cmd": ["sleep", "infinity"], "Env": ["PATH=/usr/bin"],
                   "Labels": {"version": "9.7"}},
        "HostConfig": {"Init": True, "NetworkMode": "host",
                       "RestartPolicy": {"Name": "unless-stopped"},
                       "NanoCpus": RUNTIME.NANO_CPUS,
                       "Memory": RUNTIME.MEMORY_BYTES,
                       "MemorySwap": RUNTIME.MEMORY_SWAP_BYTES},
        "Mounts": [{"Type": "bind", "Source": "/srv/example-node1",
                    "Destination": "/var/lib/docgrid", "RW": True}],
    }


class HaNodeContainerTest(unittest.TestCase):
    """Guard the pinned replacement flags and reject an unsafe live mount."""

    def setUp(self):
        self.image = {"Id": "sha256:example-image-id",
                      "Config": {"Env": ["PATH=/usr/bin"],
                                 "Labels": {"version": "9.7"}}}

    def test_constructor_preserves_resources_and_adds_init(self):
        command = RUNTIME.create_command(NODE)
        self.assertEqual(["docker", "run", "-d", "--init"], command[:4])
        for expected in ("--network", "host", "--cpus", "2", "--memory", "6g",
                         "--memory-swap", "12g", RUNTIME.captured_image(NODE)):
            self.assertIn(expected, command)
        self.assertIn("type=bind,source=/srv/example-node1,target=/var/lib/docgrid", command)
        with self.assertRaises(ValueError):
            RUNTIME.create_command("unrelated-container")

    def test_runtime_contract_accepts_only_reaping_pid_one(self):
        container = valid_container()
        self.assertEqual([], RUNTIME.configuration_errors(NODE, container, self.image,
                                                           "docker-init\n"))
        container["HostConfig"]["Init"] = False
        errors = RUNTIME.configuration_errors(NODE, container, self.image, "sleep\n")
        self.assertIn("init", errors)
        self.assertIn("pid1", errors)
        self.assertNotIn("/srv/example-node1", ",".join(errors))

    def test_capture_refuses_existing_local_image(self):
        old = valid_container()
        old["Config"]["Image"] = RUNTIME.BASE_IMAGE
        old["HostConfig"]["Init"] = False
        base = self.image

        def inspect(*args):
            return old if args[0] == "container" else base

        def command(args, **_kwargs):
            if args[:2] == ["docker", "exec"]:
                return subprocess.CompletedProcess(args, 0, "sleep")
            if args[:3] == ["docker", "image", "inspect"]:
                return subprocess.CompletedProcess(args, 0)
            self.fail("Docker commit ran despite an existing capture")

        with patch.object(RUNTIME.socket, "gethostname", return_value=NODE), \
             patch.object(RUNTIME, "docker_json", side_effect=inspect), \
             patch.object(RUNTIME.subprocess, "run", side_effect=command):
            with self.assertRaisesRegex(RuntimeError, "already exists"):
                RUNTIME.capture(NODE)

    def test_capture_preserves_original_layer_before_create(self):
        old = valid_container()
        old["Config"]["Image"] = RUNTIME.BASE_IMAGE
        old["HostConfig"]["Init"] = False
        base = self.image

        def inspect(*args):
            return old if args[0] == "container" else base

        commands = []

        def command(args, **_kwargs):
            commands.append(args)
            if args[:2] == ["docker", "exec"]:
                return subprocess.CompletedProcess(args, 0, "sleep")
            if args[:3] == ["docker", "image", "inspect"]:
                return subprocess.CompletedProcess(args, 1)
            return subprocess.CompletedProcess(args, 0)

        with patch.object(RUNTIME.socket, "gethostname", return_value=NODE), \
             patch.object(RUNTIME, "docker_json", side_effect=inspect), \
             patch.object(RUNTIME.subprocess, "run", side_effect=command):
            RUNTIME.capture(NODE)
        self.assertIn(["docker", "commit", "--pause=false", NODE,
                       RUNTIME.captured_image(NODE)], commands)

    def test_create_refuses_existing_target_before_docker_run(self):
        with patch.object(RUNTIME.socket, "gethostname", return_value=NODE), \
             patch.object(Path, "is_file", return_value=True), \
             patch.object(RUNTIME.subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess([], 0)
            with self.assertRaisesRegex(RuntimeError, "already exists"):
                RUNTIME.create(NODE)
            self.assertEqual(1, run.call_count)
            self.assertEqual(["docker", "container", "inspect", NODE],
                             run.call_args.args[0])

    def test_create_rejects_a_running_container_using_same_data(self):
        def command(args, **_kwargs):
            if args[:3] == ["docker", "container", "inspect"]:
                return subprocess.CompletedProcess(args, 1)
            if args == ["docker", "ps", "-q"]:
                return subprocess.CompletedProcess(args, 0, "other-id\n")
            self.fail("Container was started despite mounted data")

        with patch.object(RUNTIME.socket, "gethostname", return_value=NODE), \
             patch.object(Path, "is_file", return_value=True), \
             patch.object(RUNTIME.subprocess, "run", side_effect=command), \
             patch.object(RUNTIME, "docker_json", return_value={
                 "Mounts": [{"Source": "/srv/example-node1"}]}):
            with self.assertRaisesRegex(RuntimeError, "mounted by a running container"):
                RUNTIME.create(NODE)


if __name__ == "__main__":
    unittest.main()
