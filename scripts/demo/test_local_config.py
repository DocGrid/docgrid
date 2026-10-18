"""원격 대상·설정 실행·잘못된 DB 바인딩과 Ollama 필수화 회귀를 방지한다."""
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from local_config import ConfigError, LocalConfig, local_url, read_env


class LocalConfigTest(unittest.TestCase):
    """예제 형식과 실제 Compose 대조의 판정만 검사하며 외부 서비스를 호출하지 않는다."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'frontend').mkdir()
        (self.root / '.env').write_text('DB_PASSWORD=synthetic-local-only\nJWT_SECRET=synthetic-not-a-real-secret\n')

    def load(self, extra=None):
        return LocalConfig.load(self.root, environ=extra or {})

    def test_defaults_match_local_quickstart(self):
        c = self.load()
        self.assertEqual('55432', c.values['DB_PORT'])
        self.assertEqual('local', c.values['SPRING_PROFILES_ACTIVE'])

    def test_environment_overrides_file(self):
        self.assertEqual('other', self.load({'DB_NAME': 'other'}).values['DB_NAME'])

    def test_shell_substitution_is_refused_without_execution(self):
        marker = self.root / 'never-created'
        (self.root / '.env').write_text(f'DB_PASSWORD=$(touch {marker})\n')
        with self.assertRaises(ConfigError): self.load()
        self.assertFalse(marker.exists())

    def test_duplicate_key_is_refused(self):
        (self.root / '.env').write_text('DB_PASSWORD=first\nDB_PASSWORD=second\n')
        with self.assertRaises(ConfigError): self.load()

    def test_ambiguous_quotes_and_inline_comments_are_refused(self):
        for value in ['"quoted"', 'value # comment', 'value\\n', 'value ']:
            with self.subTest(value=value):
                (self.root / '.env').write_text('DB_PASSWORD='+value+'\n')
                with self.assertRaises(ConfigError): self.load()

    def test_remote_profile_and_storage_are_refused(self):
        for extra in [{'SPRING_PROFILES_ACTIVE': 'opensql-ha'}, {'STORAGE_TYPE': 'gcs'}]:
            with self.subTest(extra=extra):
                with self.assertRaises(ConfigError): self.load(extra)

    def test_remote_database_and_model_are_refused(self):
        for extra in [{'DB_HOST': 'remote.invalid'}, {'EMBEDDING_SERVER_URL': 'http://remote.invalid:8000'}]:
            with self.subTest(extra=extra):
                with self.assertRaises(ConfigError): self.load(extra)

    def test_spring_or_jvm_bypass_is_refused(self):
        for key in ['SPRING_DATASOURCE_URL', 'SPRING_APPLICATION_JSON', 'JAVA_TOOL_OPTIONS', 'OPENSQL_APP_JDBC_URL',
                    'SPRING_PROFILES_GROUP_LOCAL', 'SPRING_JPA_PROPERTIES_HIBERNATE_CONNECTION_URL',
                    'SPRING_DATA_REDIS_URL']:
            with self.subTest(key=key):
                with self.assertRaises(ConfigError): self.load({key: 'controlled-bypass'})

    def test_storage_outside_checkout_is_refused(self):
        with self.assertRaises(ConfigError): self.load({'STORAGE_LOCAL_ROOT': '../outside'})

    def test_frontend_remote_or_different_api_is_refused(self):
        for url in ['http://remote.invalid:8080', 'http://localhost:9999']:
            (self.root / 'frontend' / '.env.local').write_text('BACKEND_API_URL='+url+'\n')
            with self.assertRaises(ConfigError): self.load()

    def test_url_credentials_query_and_non_http_are_refused(self):
        for url in ['http://user:pass@localhost:8000', 'http://localhost:8000?target=elsewhere', 'https://localhost:8000']:
            with self.subTest(url=url):
                with self.assertRaises(ConfigError): local_url(url, 'TEST_URL')

    def test_disabled_worker_is_refused(self):
        with self.assertRaises(ConfigError): self.load({'INDEXING_WORKER_ENABLED': 'false'})

    def test_multiline_secret_is_refused_before_logging(self):
        with self.assertRaises(ConfigError): self.load({'JWT_SECRET': 'controlled\nprivate'})

    def container(self, port='55432', cwd=None):
        return {'Id': 'controlled-container', 'Config': {'Labels': {
            'com.docker.compose.service': 'postgres',
            'com.docker.compose.project.working_dir': str(cwd or self.root)},
            'Env': ['POSTGRES_DB=app', 'POSTGRES_USER=app', 'POSTGRES_PASSWORD=synthetic-local-only']},
            'State': {'Running': True}, 'NetworkSettings': {'Ports': {
                '5432/tcp': [{'HostIp': '127.0.0.1', 'HostPort': port}]}}}

    def test_loopback_port_without_matching_container_is_refused(self):
        c = self.load()
        with patch.object(c, 'container', return_value=self.container(port='60000')):
            with self.assertRaises(ConfigError): c.check_binding('postgres', 5432, '55432')

    def test_container_from_another_checkout_is_refused(self):
        c = self.load()
        outputs = [subprocess.CompletedProcess([], 0, 'controlled-container\n', ''),
                   subprocess.CompletedProcess([], 0, json.dumps([self.container(cwd=self.root / 'other')]), '')]
        with patch.object(c, 'command', side_effect=outputs):
            with self.assertRaises(ConfigError): c.container('postgres')

    def test_database_check_is_readonly_and_password_is_not_an_argument(self):
        c = self.load()
        with patch.object(c, 'check_binding'), patch.object(c, 'command', return_value=
            subprocess.CompletedProcess([], 0, 'app|app|0.8.1\n', '')) as command:
            c.check_database()
        args, kwargs = command.call_args
        self.assertNotIn('synthetic-local-only', ' '.join(args[0]))
        self.assertIn('SELECT', args[0][-1])
        self.assertNotIn('CREATE', args[0][-1])
        self.assertEqual('synthetic-local-only', kwargs['extra_env']['PGPASSWORD'])

    def test_wrong_extension_version_fails(self):
        c = self.load()
        with patch.object(c, 'check_binding'), patch.object(c, 'command', return_value=
            subprocess.CompletedProcess([], 0, 'app|app|0.7.0\n', '')):
            with self.assertRaises(ConfigError): c.check_database()

    def test_remote_docker_endpoint_is_refused(self):
        c = self.load({'DOCKER_HOST': 'tcp://remote.invalid:2376'})
        with self.assertRaises(ConfigError): c.ensure_local_engine()

    def test_docker_context_takes_precedence_over_host(self):
        c = self.load({'DOCKER_CONTEXT': 'controlled-context', 'DOCKER_HOST': 'unix:///controlled-local.sock'})
        with patch.object(c, 'command', return_value=
            subprocess.CompletedProcess([], 0, 'tcp://remote.invalid:2376', '')):
            with self.assertRaises(ConfigError): c.ensure_local_engine()

    def test_missing_host_port_is_not_ready_even_if_container_runs(self):
        c = self.load()
        with patch.object(c, 'container', return_value=self.container()), patch('local_config.socket.create_connection',
            side_effect=OSError('controlled refusal')):
            with self.assertRaises(ConfigError): c.check_binding('postgres', 5432, '55432')

    def test_foreign_container_is_refused_before_up(self):
        c = self.load()
        rendered = {'services': {name: {'container_name': name} for name in
                               ['postgres', 'valkey', 'embedding-server']}}
        outputs = [subprocess.CompletedProcess([], 0, 'controlled-version', ''),
                   subprocess.CompletedProcess([], 0, json.dumps(rendered), ''),
                   subprocess.CompletedProcess([], 0, json.dumps([self.container(cwd=self.root / 'other')]), '')]
        with patch.object(c, 'command', side_effect=outputs) as command:
            with self.assertRaises(ConfigError): c.preflight_containers()
        self.assertFalse(any('up' in call.args[0] for call in command.call_args_list))


class ServiceScopeTest(unittest.TestCase):
    """실제 Docker·모델 대신 실행 파일 대체물로 search의 호출 경계를 검사한다."""

    def test_search_start_never_calls_ollama(self):
        script = Path(__file__).resolve().parents[1] / 'local-services.sh'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            calls = root / 'calls'
            for name, body in {
                'docker': 'printf "docker %s\\n" "$*" >> "$CALLS"\ncase "$*" in *valkey-cli*) echo PONG;; esac\n',
                'curl': 'printf "curl %s\\n" "$*" >> "$CALLS"\ncase "$*" in *11434*) exit 88;; esac\n',
                'python3': 'exit 0\n',
            }.items():
                file = root / name
                file.write_text('#!/bin/sh\n'+body)
                file.chmod(0o755)
            env = {**os.environ, 'PATH': str(root)+os.pathsep+os.environ['PATH'],
                'CALLS': str(calls), 'DOCGRID_LOCAL_EXPLICIT': '1', 'DOCGRID_LOCAL_SCOPE': 'search'}
            result = subprocess.run(['bash', str(script), 'start'], env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertNotIn('11434', calls.read_text())
            self.assertIn('postgres valkey embedding-server', calls.read_text())

    def test_legacy_status_still_requires_ollama(self):
        script = Path(__file__).resolve().parents[1] / 'local-services.sh'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, body in {
                'docker': 'case "$*" in *valkey-cli*) echo PONG;; esac\n',
                'curl': 'case "$*" in *11434*) exit 88;; esac\n',
                'python3': 'exit 0\n',
            }.items():
                file = root / name
                file.write_text('#!/bin/sh\n'+body)
                file.chmod(0o755)
            env = {k:v for k,v in os.environ.items() if not k.startswith('DOCGRID_LOCAL_')}
            env['PATH'] = str(root)+os.pathsep+os.environ['PATH']
            result = subprocess.run(['bash', str(script), 'status'], env=env, capture_output=True, text=True)
            self.assertEqual(1, result.returncode)
            self.assertIn('Ollama API 연결 실패', result.stderr)

    def test_explicit_rag_without_ready_ollama_does_not_start_compose(self):
        script = Path(__file__).resolve().parents[1] / 'local-services.sh'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            calls = root / 'calls'
            for name, body in {
                'docker': 'printf "docker %s\\n" "$*" >> "$CALLS"\n',
                'curl': 'exit 88\n',
            }.items():
                file = root / name
                file.write_text('#!/bin/sh\n'+body)
                file.chmod(0o755)
            env = {**os.environ, 'PATH': str(root)+os.pathsep+os.environ['PATH'], 'CALLS': str(calls),
                   'DOCGRID_LOCAL_EXPLICIT': '1', 'DOCGRID_LOCAL_SCOPE': 'rag'}
            result = subprocess.run(['bash', str(script), 'start'], env=env, capture_output=True, text=True)
            self.assertEqual(1, result.returncode)
            self.assertNotIn(' up ', calls.read_text())


if __name__ == '__main__':
    unittest.main()
