"""제어 HTTP 프로세스로 소유권·종료·잠금·설정 보존을 검사하며 실제 Docker는 기동하지 않는다."""
import contextlib
import importlib.util
import io
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'demo.py'
spec = importlib.util.spec_from_file_location('demo_runtime', SCRIPT)
demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(demo)


class RuntimeContractTest(unittest.TestCase):
    """한시적 작업공간·프로세스만 만들어 생명주기와 실패 판정을 확인한다."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'scripts/demo').mkdir(parents=True)
        (self.root / 'frontend').mkdir()
        (self.root / 'backend').mkdir()
        shutil.copyfile(SCRIPT, self.root / 'scripts/demo.py')
        for name in ['local_config.py', 'seed_demo_corpus.py']:
            shutil.copyfile(SCRIPT.parent / 'demo' / name, self.root / 'scripts/demo' / name)
        (self.root / '.env').write_text('DB_PASSWORD=controlled-private-value\nJWT_SECRET=' + 'j' * 40 + '\n')
        (self.root / '.env.example').write_text('DB_PASSWORD=controlled-private-value\n')
        (self.root / 'frontend/.env.local').write_text('BACKEND_API_URL=http://localhost:8080\n')
        (self.root / 'frontend/.env.example').write_text('BACKEND_API_URL=http://localhost:8080\n')
        binary = self.root / 'bin'
        binary.mkdir()
        docker = binary / 'docker'
        docker.write_text('#!/bin/sh\necho unix:///var/run/docker.sock\n')
        docker.chmod(0o755)
        self.environment = {'PATH': str(binary) + os.pathsep + os.environ['PATH'],
                            'HOME': os.environ['HOME'], 'CONTROLLED_PORT_FILE': str(self.root / 'port')}
        self.runtime = demo.DemoRuntime(self.root)
        self.runtime.config = demo.LocalConfig.load(self.root, environ=self.environment)
        self.runtime.config.ensure_local_engine()
        self.runtime.state = {'id': uuid.uuid4().hex, 'status': 'STARTING', 'scope': 'search',
            'processes': {}, 'containers': {}, 'before': {}, 'timeout': 5,
            'endpoint': {'base': 'absolute', 'path': '/var/run/docker.sock'},
            'engineHash': demo.digest('controlled-engine')}
        self.runtime.save()

    def start_controlled_backend(self, detached=False):
        script = self.root / 'backend/gradlew'
        script.write_text('''#!/usr/bin/env python3
import os, subprocess, sys, time
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path
if os.environ.get('CONTROLLED_DETACH') == 'true': os.setsid()
child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(120)'])
server = HTTPServer(('127.0.0.1', 0), BaseHTTPRequestHandler)
Path(os.environ['CONTROLLED_PORT_FILE']).write_text(str(server.server_port))
print('controlled-ready', os.environ['DB_PASSWORD'], os.environ['JWT_SECRET'], 'user@example.invalid', flush=True)
server.serve_forever()
''')
        script.chmod(0o755)
        if detached:
            self.runtime.config.values['CONTROLLED_DETACH'] = 'true'
        record = self.runtime.spawn('backend')
        def cleanup():
            if demo.identity(record['pid']) == record:
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(record['pgid'], signal.SIGKILL)
            handle = self.runtime.handles.get('backend')
            if handle:
                handle.wait(timeout=5)
        self.addCleanup(cleanup)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline and not (self.root / 'port').exists():
            time.sleep(0.05)
        self.assertTrue((self.root / 'port').exists())
        return record, int((self.root / 'port').read_text())

    def test_starting_component_records_actual_group_and_listener(self):
        record, port = self.start_controlled_backend()
        self.assertEqual(record, demo.identity(record['pid']))
        self.assertTrue(demo.listener_owned(port, record))
        self.assertFalse(demo.listener_owned(port, {**record, 'pgid': record['pgid'] + 100000}))

    def test_stop_releases_children_and_actual_server_port(self):
        record, port = self.start_controlled_backend()
        self.assertTrue(self.runtime.stop_process('backend'))
        self.assertFalse(demo.port_open(port))
        self.assertEqual([], demo.group_members(record['pgid']))

    def test_detached_child_is_identified_and_stopped_by_registered_identity(self):
        record, port = self.start_controlled_backend(detached=True)
        deadline = time.monotonic() + 5
        children = []
        while time.monotonic() < deadline:
            children = self.runtime.owned_children('backend')
            if demo.listener_owned(port, record, children):
                break
            time.sleep(0.05)
        self.assertFalse(demo.listener_owned(port, record))
        self.assertTrue(demo.listener_owned(port, record, children))
        self.assertTrue(self.runtime.stop_process('backend'))
        self.assertFalse(demo.port_open(port))
        self.assertFalse(any(demo.identity(item['pid']) == item for item in children))

    def test_child_record_from_another_owner_is_refused(self):
        self.start_controlled_backend()
        self.runtime.stop_process('backend')
        demo.write_json(self.runtime.component_path('backend', '.children.json'), {'owner': {}, 'children': []})
        with self.assertRaises(demo.DemoError): self.runtime.owned_children('backend')

    def test_changed_identity_never_kills_live_process(self):
        record, port = self.start_controlled_backend()
        changed = {**record, 'started': 'controlled-stale-start'}
        demo.write_json(self.runtime.component_path('backend', '.pid.json'), changed)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertFalse(self.runtime.stop_process('backend'))
        self.assertTrue(demo.port_open(port))
        self.assertEqual(record, demo.identity(record['pid']))

    def test_child_record_recovers_parent_missing_process_map(self):
        record, port = self.start_controlled_backend()
        self.runtime.state['processes'] = {}
        self.runtime.save()
        recovered = demo.DemoRuntime(self.root)
        recovered.load()
        self.assertEqual(record, recovered.state['processes']['backend'])
        self.assertTrue(recovered.stop_process('backend'))
        self.assertFalse(demo.port_open(port))

    def test_supervisor_redacts_before_writing(self):
        self.start_controlled_backend()
        log = self.runtime.component_path('backend', '.log')
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline and (not log.exists() or 'controlled-ready' not in log.read_text()):
            time.sleep(0.05)
        text = log.read_text()
        self.assertIn('controlled-ready', text)
        self.assertNotIn('controlled-private-value', text)
        self.assertNotIn('j' * 40, text)
        self.assertNotIn('user@example.invalid', text)

    def test_verify_records_use_distinct_paths(self):
        self.runtime.state['verification'] = {'id': uuid.uuid4().hex}
        first = self.runtime.component_path('verify', '.exit.json')
        demo.write_json(first, {'code': 0})
        self.runtime.state['verification'] = {'id': uuid.uuid4().hex}
        second = self.runtime.component_path('verify', '.exit.json')
        self.assertNotEqual(first, second)
        self.assertTrue(first.exists())
        self.assertFalse(second.exists())

    def test_existing_personal_environment_files_are_preserved(self):
        paths = [self.root / '.env', self.root / 'frontend/.env.local']
        before = [(p.read_bytes(), p.stat().st_mtime_ns) for p in paths]
        self.runtime.prepare_files()
        self.assertEqual(before, [(p.read_bytes(), p.stat().st_mtime_ns) for p in paths])

    def test_new_env_is_initialized_without_default_jwt(self):
        (self.root / '.env').unlink()
        self.runtime.prepare_files()
        values = demo.LocalConfig.load(self.root, environ=self.environment).values
        self.assertGreaterEqual(len(values['JWT_SECRET']), 32)
        self.assertEqual(0o600, (self.root / '.env').stat().st_mode & 0o777)

    def test_concurrent_command_lock_refuses_second_process(self):
        code = '''import importlib.util,sys
s=importlib.util.spec_from_file_location('controlled',sys.argv[1]);m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
try:
 with m.DemoRuntime(sys.argv[2]).lock(): print('unexpected-acquired')
except m.DemoError: raise SystemExit(3)
'''
        with self.runtime.lock():
            result = subprocess.run([sys.executable, '-c', code, str(SCRIPT), str(self.root)], capture_output=True, text=True)
        self.assertEqual(3, result.returncode, '동시 명령이 거부돼야 함')

    def test_changed_configuration_refuses_before_database_or_upload(self):
        with patch.object(self.runtime, 'valid_configuration', return_value=False), \
             patch.object(self.runtime.config, 'check_database') as database:
            with self.assertRaises(demo.DemoError): self.runtime.assert_ready()
        database.assert_not_called()

    def test_frontend_proxy_comparison_ignores_response_timestamp(self):
        record = {'pid': 123, 'pgid': 123, 'started': 'controlled', 'commandHash': 'controlled'}
        self.runtime.state['processes']['frontend'] = record
        bodies = [{'success': True, 'data': [1], 'timestamp': 'first'},
                  {'success': True, 'data': [1], 'timestamp': 'second'}]
        with patch.object(demo, 'identity', return_value=record), \
             patch.object(demo, 'listener_owned', return_value=True), \
             patch.object(demo, 'http_json', side_effect=bodies), \
             patch.object(demo, 'http_bytes', return_value=b'DocGrid login'):
            self.assertTrue(self.runtime.frontend_ready())

    def test_stop_preserves_reused_containers(self):
        self.runtime.state['status'] = 'READY'
        self.runtime.state['containers'] = {'postgres': {'id': 'controlled-id', 'owned': False}}
        with patch.object(demo.subprocess, 'run', return_value=
                          subprocess.CompletedProcess([], 0, 'controlled-engine\n', '')) as command, \
             contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, self.runtime.stop())
        self.assertFalse(any('stop' in call.args[0] for call in command.call_args_list))

    def test_partial_start_failure_runs_cleanup(self):
        self.runtime.state = None
        with patch.object(self.runtime, 'check_tools'), patch.object(self.runtime, 'prepare_files'), \
             patch.object(self.runtime, 'load', return_value=None), \
             patch.object(demo.LocalConfig, 'load', return_value=self.runtime.config), \
             patch.object(self.runtime.config, 'preflight_containers'), \
             patch.object(self.runtime.config, 'command', return_value=subprocess.CompletedProcess([], 0, 'controlled-engine\n', '')), \
             patch.object(self.runtime, 'snapshot', return_value={s: None for s in demo.SERVICES}), \
             patch.object(demo, 'source_fingerprint', return_value='controlled'), \
             patch.object(demo, 'port_open', return_value=False), \
             patch.object(self.runtime, 'spawn', side_effect=demo.DemoError('controlled failure')), \
             patch.object(self.runtime, 'stop', return_value=0) as stop, \
             contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(demo.DemoError): self.runtime.start('search', 5)
        stop.assert_called_once()

    def test_container_transition_preserves_reused_and_marks_newly_started(self):
        self.runtime.state['before'] = {'postgres': {'id': 'same-db', 'running': True},
                                       'valkey': {'id': 'same-cache', 'running': False}, 'embedding-server': None}
        current = {'postgres': {'id': 'same-db', 'running': True},
                   'valkey': {'id': 'same-cache', 'running': True},
                   'embedding-server': {'id': 'new-model', 'running': True}}
        with patch.object(self.runtime, 'snapshot', return_value=current):
            self.runtime.reconcile_containers()
        self.assertFalse(self.runtime.state['containers']['postgres']['owned'])
        self.assertTrue(self.runtime.state['containers']['valkey']['owned'])
        self.assertTrue(self.runtime.state['containers']['embedding-server']['owned'])

    def test_session_symlink_cannot_redirect_state_writes(self):
        outside = self.root / 'outside'
        outside.mkdir()
        other_id = uuid.uuid4().hex
        (self.runtime.directory / 'sessions' / other_id).symlink_to(outside, target_is_directory=True)
        with self.assertRaises(demo.DemoError): self.runtime.session_path(other_id)

    def test_status_does_not_report_dead_ready_session_as_success(self):
        self.runtime.state['status'] = 'READY'
        self.runtime.save()
        with patch.object(demo.LocalConfig, 'load', return_value=self.runtime.config), \
             patch.object(self.runtime, 'assert_ready', side_effect=demo.DemoError('controlled not ready')), \
             contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, self.runtime.status())


if __name__ == '__main__':
    unittest.main()
