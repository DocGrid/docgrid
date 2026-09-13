"""Run isolated, measurable incident drills against DocGrid's real monitoring path."""

import argparse
import concurrent.futures
import json
import math
import os
import platform
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import traceback
import uuid
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path


DRILL_DIR = Path(__file__).resolve().parent
ROOT_DIR = DRILL_DIR.parents[1]
COMPOSE_FILE = DRILL_DIR / "docker-compose.yml"
CLUSTER = "docgrid-drill"
ENVIRONMENT = "local-drill"
SNAPSHOT_QUERY_CALLS_SQL = """
SELECT COALESCE(SUM(calls), 0)::bigint
FROM pg_stat_statements
WHERE query NOT ILIKE '%pg_stat_statements%'
  AND (
    (query ILIKE '%FROM embedding_jobs job%' AND query ILIKE '%AS claimable_jobs%')
    OR (query ILIKE '%FROM rag_responses response%' AND query ILIKE '%AS oldest_processing_at%')
    OR (query ILIKE '%FROM sync_outbox_events event%' AND query ILIKE '%AS claimable_events%')
  )
"""


def utc_now():
    """Return an RFC 3339 UTC timestamp suitable for result evidence and Alertmanager."""
    return datetime.now(timezone.utc)


def iso(timestamp=None):
    """Serialize a UTC timestamp without losing subsecond measurement context."""
    return (timestamp or utc_now()).isoformat()


def free_port():
    """Reserve an available loopback port long enough to choose distinct Backend listeners."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def run(command, *, cwd=ROOT_DIR, env=None, capture=True, check=True):
    """Run one external command without a shell so measured values cannot become shell code."""
    return subprocess.run(
        [str(part) for part in command],
        cwd=cwd,
        env=env,
        check=check,
        text=True,
        capture_output=capture,
    )


def request(url, *, method="GET", payload=None, headers=None, timeout=10):
    """Perform one local HTTP request and return status plus raw response bytes."""
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    request_headers = dict(headers or {})
    if body is not None:
        request_headers["Content-Type"] = "application/json"
    http_request = urllib.request.Request(url, data=body, headers=request_headers, method=method)
    with urllib.request.urlopen(http_request, timeout=timeout) as response:
        return response.status, response.read()


def request_json(url, *, method="GET", payload=None, headers=None, timeout=10):
    """Perform one local HTTP request and decode a non-empty JSON body."""
    status, body = request(url, method=method, payload=payload, headers=headers, timeout=timeout)
    return status, None if not body else json.loads(body)


def wait_for(description, predicate, timeout_seconds, interval_seconds=1):
    """Poll an observable boundary with a deadline and periodic progress output."""
    deadline = time.monotonic() + timeout_seconds
    next_progress = time.monotonic() + 30
    last_error = None
    while time.monotonic() < deadline:
        try:
            value = predicate()
            if value:
                return value
        except (AssertionError, OSError, ValueError, urllib.error.URLError) as error:
            last_error = error
        now = time.monotonic()
        if now >= next_progress:
            print(f"Waiting for {description}...", flush=True)
            next_progress = now + 30
        time.sleep(interval_seconds)
    detail = f": {last_error}" if last_error else ""
    raise TimeoutError(f"Timed out after {timeout_seconds}s waiting for {description}{detail}")


def nearest_rank(values, percentile):
    """Calculate an explicit nearest-rank percentile without external benchmark libraries."""
    ordered = sorted(values)
    index = max(0, math.ceil(percentile * len(ordered)) - 1)
    return ordered[index]


def build_backend():
    """Build the executable Backend jar once before scenarios that launch real processes."""
    print("Building Backend bootJar...", flush=True)
    run(["./backend/gradlew", "-p", "backend", "bootJar", "--no-daemon"], capture=False)
    candidates = sorted((ROOT_DIR / "backend" / "build" / "libs").glob("*.jar"))
    candidates = [candidate for candidate in candidates if not candidate.name.endswith("-plain.jar")]
    if len(candidates) != 1:
        raise RuntimeError(f"Expected one executable jar, found: {candidates}")
    return candidates[0]


class DrillEnvironment:
    """Own one isolated Compose project, its Backend processes, ports, logs, and cleanup boundary."""

    def __init__(self, scenario, output_dir):
        self.scenario = scenario
        self.output_dir = output_dir
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.log_dir = self.output_dir / "logs" / scenario
        self.log_dir.mkdir(parents=True, exist_ok=True)
        self.temp_dir = Path(tempfile.mkdtemp(prefix=f"docgrid-{scenario}-"))
        (self.temp_dir / "webhook").mkdir()
        (self.temp_dir / "webhook" / "events.jsonl").touch()
        self.project_name = f"docgrid-drill-{scenario.replace('_', '-')}-{os.getpid()}"
        self.compose_env = os.environ.copy()
        self.compose_env["DRILL_TMP_DIR"] = str(self.temp_dir)
        self.backend_processes = []
        self.backend_logs = []
        self.postgres_port = None
        self.redis_port = None
        self.embedding_port = None
        self.prometheus_url = None
        self.alertmanager_url = None

    def compose(self, *arguments, capture=True, check=True):
        """Address only this scenario's Compose project."""
        return run(
            ["docker", "compose", "-p", self.project_name, "-f", COMPOSE_FILE, *arguments],
            cwd=DRILL_DIR,
            env=self.compose_env,
            capture=capture,
            check=check,
        )

    def start_core(self, *, include_provider):
        """Start fresh database, cache, local receiver, Alertmanager, and optionally BGE-M3."""
        services = ["postgres", "redis", "webhook-receiver", "alertmanager"]
        if include_provider:
            run(["docker", "volume", "create", "docgrid_huggingface-cache"])
            services.append("embedding-server")
        print(f"Starting isolated services for {self.scenario}...", flush=True)
        self.compose("up", "-d", "--wait", *services, capture=False)
        self.postgres_port = self.service_port("postgres", 5432)
        self.redis_port = self.service_port("redis", 6379)
        self.alertmanager_url = f"http://127.0.0.1:{self.service_port('alertmanager', 9093)}"
        if include_provider:
            self.embedding_port = self.service_port("embedding-server", 8000)

    def service_port(self, service, container_port):
        """Resolve a dynamic host port assigned to one service."""
        address = self.compose("port", service, str(container_port)).stdout.strip()
        return int(address.rsplit(":", 1)[1])

    def psql(self, sql):
        """Execute one SQL statement inside this scenario's PostgreSQL container."""
        result = self.compose(
            "exec", "-T", "postgres", "psql", "-v", "ON_ERROR_STOP=1",
            "-U", "app", "-d", "app", "-Atc", sql,
        )
        return result.stdout.strip()

    def start_backend(self, jar_path, name, *, worker_enabled, snapshot_interval):
        """Launch a real Backend process against this scenario's database and shared local files."""
        api_port = free_port()
        management_port = free_port()
        log_path = self.log_dir / f"backend-{name}.log"
        log_file = log_path.open("w", encoding="utf-8")
        environment = os.environ.copy()
        environment.update({
            "SPRING_PROFILES_ACTIVE": "local",
            "SPRING_DEVTOOLS_RESTART_ENABLED": "false",
            "DB_HOST": "127.0.0.1",
            "DB_PORT": str(self.postgres_port),
            "DB_NAME": "app",
            "DB_USER": "app",
            "DB_PASSWORD": "drill_password",
            "DB_SCHEMA": "public",
            "DB_SSLMODE": "disable",
            "REDIS_HOST": "127.0.0.1",
            "REDIS_PORT": str(self.redis_port),
            "JWT_SECRET": "docgrid-observability-incident-drill-secret-key-2026",
            "JWT_EXPIRATION": "3600",
            "MINIO_ENDPOINT": "http://127.0.0.1:65534",
            "MINIO_ACCESS_KEY": "unused",
            "MINIO_SECRET_KEY": "unused",
            "STORAGE_TYPE": "local",
            "STORAGE_BUCKET": "docgrid-drill",
            "STORAGE_LOCAL_ROOT": str(self.temp_dir / "storage"),
            "EMBEDDING_SERVER_URL": (
                f"http://127.0.0.1:{self.embedding_port}"
                if self.embedding_port else "http://127.0.0.1:65534"
            ),
            "INDEXING_WORKER_ENABLED": str(worker_enabled).lower(),
            "INDEXING_WORKER_NAME": f"observability-drill-{name}",
            "INDEXING_WORKER_POLLING_INTERVAL": "200ms",
            "INDEXING_WORKER_IDLE_MAX_POLLING_INTERVAL": "1s",
            "INDEXING_WORKER_HEARTBEAT_INTERVAL": "1s",
            "INDEXING_WORKER_DEAD_THRESHOLD": "30s",
            "INDEXING_WORKER_MAX_CONCURRENCY": "1",
            "SYNC_DISPATCHER_ENABLED": "false",
            "SYNC_RECONCILIATION_ENABLED": "false",
            "MANAGEMENT_METRICS_SNAPSHOT_INTERVAL": snapshot_interval,
            "SERVER_PORT": str(api_port),
            "MANAGEMENT_PORT": str(management_port),
        })
        process = subprocess.Popen(
            ["java", "-jar", str(jar_path)],
            cwd=ROOT_DIR,
            env=environment,
            stdout=log_file,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        self.backend_processes.append(process)
        self.backend_logs.append(log_file)
        readiness_url = f"http://127.0.0.1:{management_port}/actuator/health/readiness"
        try:
            wait_for(
                f"Backend {name} readiness",
                lambda: request(readiness_url, timeout=2)[0] == 200,
                180,
            )
        except Exception:
            log_file.flush()
            raise RuntimeError(f"Backend {name} did not become ready; see {log_path}")
        return {
            "name": name,
            "apiPort": api_port,
            "managementPort": management_port,
            "metricsUrl": f"http://127.0.0.1:{management_port}/actuator/prometheus",
        }

    def write_prometheus_config(self, backend_management_port=None):
        """Render the production rule files with this scenario's concrete Backend target."""
        scrape_configs = []
        if backend_management_port is not None:
            scrape_configs.append(f"""
  - job_name: docgrid-backend
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: [\"host.docker.internal:{backend_management_port}\"]
        labels:
          cluster: {CLUSTER}
          environment: {ENVIRONMENT}
""")
        scrape_configs.append("""
  - job_name: embedding-provider
    metrics_path: /metrics
    static_configs:
      - targets: ["embedding-server:8000"]
        labels:
          cluster: docgrid-drill
          environment: local-drill
""")
        config = """global:
  scrape_interval: 15s
  evaluation_interval: 15s

rule_files:
  - /etc/prometheus/rules/*.yml

alerting:
  alertmanagers:
    - static_configs:
        - targets: [\"alertmanager:9093\"]

scrape_configs:
""" + "".join(scrape_configs)
        config_path = self.temp_dir / "prometheus.yml"
        config_path.write_text(config, encoding="utf-8")
        shutil.copy2(config_path, self.log_dir / "prometheus.yml")

    def start_prometheus(self):
        """Start Prometheus after its dynamic host target configuration has been rendered."""
        self.compose("up", "-d", "--wait", "prometheus", capture=False)
        self.prometheus_url = f"http://127.0.0.1:{self.service_port('prometheus', 9090)}"

    def prometheus_query(self, expression):
        """Return the vector result of one instant Prometheus query."""
        query = urllib.parse.urlencode({"query": expression})
        _, response = request_json(f"{self.prometheus_url}/api/v1/query?{query}")
        if response["status"] != "success":
            raise AssertionError(response)
        return response["data"]["result"]

    def prometheus_value(self, expression):
        """Return the first numeric instant-vector sample, or None when the vector is absent."""
        result = self.prometheus_query(expression)
        return None if not result else float(result[0]["value"][1])

    def prometheus_alert(self, alertname, expected_state=None):
        """Find a production rule alert and optionally require its pending or firing state."""
        _, response = request_json(f"{self.prometheus_url}/api/v1/alerts")
        for alert in response["data"]["alerts"]:
            if alert["labels"].get("alertname") != alertname:
                continue
            if expected_state is None or alert.get("state") == expected_state:
                return alert
        return None

    def webhook_events(self):
        """Read complete timestamped webhook deliveries while tolerating a concurrently written file."""
        path = self.temp_dir / "webhook" / "events.jsonl"
        events = []
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                events.append(json.loads(line))
        return events

    def webhook_delivery(self, alertname, status):
        """Find the first webhook containing one alert with the requested name and lifecycle status."""
        for event in self.webhook_events():
            for alert in event["payload"].get("alerts", []):
                if (alert.get("labels", {}).get("alertname") == alertname
                        and alert.get("status") == status):
                    return event
        return None

    def alertmanager_alert(self, alertname):
        """Find one active Alertmanager alert, including its inhibition state."""
        _, alerts = request_json(f"{self.alertmanager_url}/api/v2/alerts")
        return next(
            (alert for alert in alerts if alert.get("labels", {}).get("alertname") == alertname),
            None,
        )

    def post_alertmanager_alert(self, labels, *, starts_at, ends_at):
        """Inject one explicitly labeled dependent alert to test inhibition against a real root cause."""
        payload = [{
            "labels": labels,
            "annotations": {"summary": "Incident drill dependent warning"},
            "startsAt": iso(starts_at),
            "endsAt": iso(ends_at),
            "generatorURL": "https://github.com/DocGrid/docgrid/issues/338",
        }]
        request(f"{self.alertmanager_url}/api/v2/alerts", method="POST", payload=payload)

    def close(self):
        """Stop child processes, persist bounded diagnostic evidence, and remove scenario state."""
        for process in reversed(self.backend_processes):
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                    process.wait(timeout=20)
                except (ProcessLookupError, subprocess.TimeoutExpired):
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
        for log_file in self.backend_logs:
            log_file.close()

        event_log = self.temp_dir / "webhook" / "events.jsonl"
        if event_log.exists():
            shutil.copy2(event_log, self.log_dir / "webhook-events.jsonl")
        compose_logs = self.compose("logs", "--no-color", "--tail", "300", check=False)
        (self.log_dir / "compose.log").write_text(compose_logs.stdout + compose_logs.stderr, encoding="utf-8")
        self.compose("down", "--volumes", "--remove-orphans", "--rmi", "local", check=False)
        shutil.rmtree(self.temp_dir, ignore_errors=True)


def metric_value(metrics_text, name, labels=None):
    """Extract one Prometheus text sample with an optional exact label subset."""
    required_labels = labels or {}
    for line in metrics_text.splitlines():
        if not line.startswith(name):
            continue
        sample, raw_value = line.rsplit(" ", 1)
        if any(f'{key}="{value}"' not in sample for key, value in required_labels.items()):
            continue
        return float(raw_value)
    raise AssertionError(f"Metric {name} with labels {required_labels} was not exposed")


def fetch_metrics(backend):
    """Read the real Spring Boot Prometheus endpoint as text."""
    _, body = request(backend["metricsUrl"], timeout=10)
    return body.decode("utf-8")


def login_and_upload(backend, temp_dir):
    """Create a real pending Embedding Job through authentication and multipart upload APIs."""
    _, login_response = request_json(
        f"http://127.0.0.1:{backend['apiPort']}/auth/login",
        method="POST",
        payload={"email": "kcw130502@gmail.com", "password": "admin1234"},
    )
    access_token = login_response["data"]["accessToken"]
    document_path = temp_dir / "queue-stall.txt"
    document_path.write_text(
        "A recovered indexing worker must claim this queued document and complete its embedding.",
        encoding="utf-8",
    )
    boundary = f"docgrid-drill-{uuid.uuid4().hex}"
    parts = []

    def append_field(name, value):
        parts.extend([
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(),
            str(value).encode(),
            b"\r\n",
        ])

    parts.extend([
        f"--{boundary}\r\n".encode(),
        b'Content-Disposition: form-data; name="file"; filename="queue-stall.txt"\r\n',
        b"Content-Type: text/plain\r\n\r\n",
        document_path.read_bytes(),
        b"\r\n",
    ])
    append_field("title", "Observability Queue Recovery Drill")
    append_field("description", "Actual queue alert and worker recovery")
    append_field("visibility", "PRIVATE")
    parts.append(f"--{boundary}--\r\n".encode())
    upload_request = urllib.request.Request(
        f"http://127.0.0.1:{backend['apiPort']}/api/documents",
        data=b"".join(parts),
        headers={
            "Authorization": f"Bearer {access_token}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
        method="POST",
    )
    with urllib.request.urlopen(upload_request, timeout=30) as response:
        payload = json.loads(response.read())
    return payload["data"]


def base_result(scenario):
    """Record reproducibility metadata shared by all measured scenarios."""
    commit = run(["git", "rev-parse", "HEAD"]).stdout.strip()
    return {
        "scenario": scenario,
        "status": "PASS",
        "measuredAt": iso(),
        "gitCommit": commit,
        "host": {"system": platform.system(), "machine": platform.machine()},
        "images": {
            "postgres": "pgvector/pgvector:0.8.1-pg17",
            "prometheus": "prom/prometheus:v3.5.5",
            "alertmanager": "quay.io/prometheus/alertmanager:v0.33.1",
        },
    }


def run_scrape_load(output_dir, jar_path):
    """Measure endpoint latency and prove repeated scrapes do not execute operational SQL."""
    environment = DrillEnvironment("scrape-load", output_dir)
    try:
        environment.start_core(include_provider=False)
        backend = environment.start_backend(
            jar_path, "metrics", worker_enabled=False, snapshot_interval="10m"
        )
        wait_for(
            "the first operational snapshot",
            lambda: metric_value(
                fetch_metrics(backend),
                "docgrid_operational_snapshot_refresh_total",
                {"outcome": "success"},
            ) >= 1,
            30,
        )

        initial_snapshot_calls = int(environment.psql(SNAPSHOT_QUERY_CALLS_SQL))
        if initial_snapshot_calls != 3:
            raise AssertionError(f"Expected three initial aggregate queries, got {initial_snapshot_calls}")
        refresh_before = metric_value(
            fetch_metrics(backend),
            "docgrid_operational_snapshot_refresh_total",
            {"outcome": "success"},
        )
        environment.psql("SELECT pg_stat_statements_reset()")

        request_count = int(os.environ.get("DRILL_SCRAPE_REQUESTS", "300"))
        concurrency = int(os.environ.get("DRILL_SCRAPE_CONCURRENCY", "20"))

        def measured_scrape(_):
            started = time.perf_counter()
            metrics = fetch_metrics(backend)
            metric_value(metrics, "docgrid_embedding_claimable_jobs")
            return (time.perf_counter() - started) * 1000

        print(f"Running {request_count} scrapes with concurrency {concurrency}...", flush=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
            latencies = list(executor.map(measured_scrape, range(request_count)))

        snapshot_calls_during_scrape = int(environment.psql(SNAPSHOT_QUERY_CALLS_SQL))
        refresh_after = metric_value(
            fetch_metrics(backend),
            "docgrid_operational_snapshot_refresh_total",
            {"outcome": "success"},
        )
        if snapshot_calls_during_scrape != 0:
            raise AssertionError(
                f"Scrapes executed {snapshot_calls_during_scrape} operational aggregate queries"
            )
        if refresh_after != refresh_before:
            raise AssertionError("Snapshot scheduler refreshed during the isolated scrape measurement")

        result = base_result("scrape-load")
        result["conditions"] = {
            "requests": request_count,
            "concurrency": concurrency,
            "snapshotInterval": "10m",
            "endpoint": "/actuator/prometheus",
        }
        result["measurements"] = {
            "initialSnapshotAggregateSqlCalls": initial_snapshot_calls,
            "aggregateSqlCallsCausedByScrapes": snapshot_calls_during_scrape,
            "snapshotRefreshesDuringScrapes": int(refresh_after - refresh_before),
            "latencyMilliseconds": {
                "p50": round(nearest_rank(latencies, 0.50), 3),
                "p95": round(nearest_rank(latencies, 0.95), 3),
                "max": round(max(latencies), 3),
            },
        }
        return result
    finally:
        environment.close()


def run_provider_outage(output_dir):
    """Stop and restore the real BGE-M3 container through production Prometheus alert rules."""
    environment = DrillEnvironment("provider-outage", output_dir)
    try:
        environment.start_core(include_provider=True)
        environment.write_prometheus_config()
        environment.start_prometheus()
        wait_for(
            "Prometheus to scrape the ready Embedding Provider",
            lambda: environment.prometheus_value('up{job="embedding-provider"}') == 1,
            60,
        )

        print("Stopping the real Embedding Provider container...", flush=True)
        stopped_at = utc_now()
        environment.compose("stop", "embedding-server", capture=False)
        condition_at = wait_for(
            "Prometheus up=0 provider condition",
            lambda: utc_now() if environment.prometheus_value(
                'up{job="embedding-provider"}'
            ) == 0 else None,
            60,
        )
        pending_at = wait_for(
            "EmbeddingProviderDown pending state",
            lambda: utc_now() if environment.prometheus_alert(
                "EmbeddingProviderDown", "pending"
            ) else None,
            60,
        )
        firing_at = wait_for(
            "EmbeddingProviderDown firing state",
            lambda: utc_now() if environment.prometheus_alert(
                "EmbeddingProviderDown", "firing"
            ) else None,
            120,
            2,
        )
        firing_delivery = wait_for(
            "EmbeddingProviderDown firing webhook",
            lambda: environment.webhook_delivery("EmbeddingProviderDown", "firing"),
            60,
        )

        # A real Provider root cause is combined with one explicit downstream warning to isolate inhibition.
        derived_labels = {
            "alertname": "DocGridEmbeddingQueueStalled",
            "cluster": CLUSTER,
            "environment": ENVIRONMENT,
            "severity": "warning",
            "service": "embedding-worker",
            "dependency": "embedding-provider",
        }
        derived_started = utc_now()
        environment.post_alertmanager_alert(
            derived_labels,
            starts_at=derived_started,
            ends_at=derived_started + timedelta(minutes=10),
        )
        inhibited = wait_for(
            "dependent warning inhibition",
            lambda: (
                alert if (alert := environment.alertmanager_alert(
                    "DocGridEmbeddingQueueStalled"
                )) and alert.get("status", {}).get("state") == "suppressed" else None
            ),
            30,
        )
        time.sleep(35)
        if environment.webhook_delivery("DocGridEmbeddingQueueStalled", "firing"):
            raise AssertionError("The inhibited dependent warning reached the webhook receiver")
        environment.post_alertmanager_alert(
            derived_labels,
            starts_at=derived_started,
            ends_at=utc_now(),
        )

        print("Restarting the real Embedding Provider container...", flush=True)
        recovery_started = utc_now()
        environment.compose("start", "embedding-server", capture=False)
        # Docker may assign a new ephemeral host port when a stopped container starts again.
        environment.embedding_port = environment.service_port("embedding-server", 8000)
        wait_for(
            "Embedding Provider readiness after restart",
            lambda: request(
                f"http://127.0.0.1:{environment.embedding_port}/health/ready",
                timeout=5,
            )[0] == 200,
            900,
            5,
        )
        provider_ready_at = utc_now()
        wait_for(
            "Prometheus to observe provider recovery",
            lambda: environment.prometheus_value('up{job="embedding-provider"}') == 1,
            60,
        )
        wait_for(
            "EmbeddingProviderDown rule resolution",
            lambda: not environment.prometheus_alert("EmbeddingProviderDown"),
            60,
        )
        resolved_delivery = wait_for(
            "EmbeddingProviderDown resolved webhook",
            lambda: environment.webhook_delivery("EmbeddingProviderDown", "resolved"),
            90,
        )

        firing_received = datetime.fromisoformat(firing_delivery["receivedAt"])
        resolved_received = datetime.fromisoformat(resolved_delivery["receivedAt"])
        result = base_result("provider-outage")
        result["conditions"] = {
            "scrapeInterval": "15s",
            "evaluationInterval": "15s",
            "productionRuleFor": "1m",
            "criticalGroupWait": "10s",
            "testGroupInterval": "30s",
            "productionGroupInterval": "5m",
        }
        result["timestamps"] = {
            "providerStoppedAt": iso(stopped_at),
            "upZeroObservedAt": iso(condition_at),
            "alertPendingAt": iso(pending_at),
            "alertFiringAt": iso(firing_at),
            "firingWebhookReceivedAt": iso(firing_received),
            "recoveryStartedAt": iso(recovery_started),
            "providerReadyAt": iso(provider_ready_at),
            "resolvedWebhookReceivedAt": iso(resolved_received),
        }
        result["measurements"] = {
            "stopToConditionSeconds": round((condition_at - stopped_at).total_seconds(), 3),
            "stopToFiringSeconds": round((firing_at - stopped_at).total_seconds(), 3),
            "stopToWebhookSeconds": round((firing_received - stopped_at).total_seconds(), 3),
            "restartToReadySeconds": round((provider_ready_at - recovery_started).total_seconds(), 3),
            "restartToResolvedWebhookSeconds": round(
                (resolved_received - recovery_started).total_seconds(), 3
            ),
            "dependentWarningInhibited": bool(inhibited),
            "dependentWarningWebhookDeliveries": sum(
                1 for event in environment.webhook_events()
                for alert in event["payload"].get("alerts", [])
                if alert.get("labels", {}).get("alertname") == "DocGridEmbeddingQueueStalled"
                and alert.get("status") == "firing"
            ),
        }
        return result
    finally:
        environment.close()


def run_queue_recovery(output_dir, jar_path):
    """Create a real queued document, observe production alerts, then recover it with a Worker."""
    environment = DrillEnvironment("queue-recovery", output_dir)
    try:
        environment.start_core(include_provider=True)
        observer = environment.start_backend(
            jar_path, "observer", worker_enabled=False, snapshot_interval="2s"
        )
        upload = login_and_upload(observer, environment.temp_dir)
        job_id = int(upload["embeddingJobId"])

        # Keep this experiment scoped to Embedding Queue alerts; Outbox behavior has its own rule suite.
        environment.psql(
            "UPDATE sync_outbox_events SET status='PROCESSED', processed_at=CURRENT_TIMESTAMP, "
            "updated_at=CURRENT_TIMESTAMP WHERE event_id=(SELECT source_event_id FROM embedding_jobs "
            f"WHERE id={job_id})"
        )
        environment.psql(
            "UPDATE embedding_jobs SET created_at=CURRENT_TIMESTAMP - INTERVAL '10 minutes', "
            f"next_retry_at=NULL WHERE id={job_id}"
        )
        wait_for(
            "real claimable Queue gauges",
            lambda: (
                metrics if (
                    metric_value(metrics := fetch_metrics(observer), "docgrid_embedding_claimable_jobs") >= 1
                    and metric_value(metrics, "docgrid_embedding_active_workers") == 0
                    and metric_value(
                        metrics, "docgrid_embedding_oldest_claimable_age_seconds"
                    ) > 300
                ) else None
            ),
            30,
        )

        environment.write_prometheus_config(observer["managementPort"])
        environment.start_prometheus()
        wait_for(
            "Prometheus to scrape the real Backend",
            lambda: environment.prometheus_value('up{job="docgrid-backend"}') == 1,
            60,
        )
        condition_at = wait_for(
            "Prometheus Queue condition",
            lambda: utc_now() if (
                (environment.prometheus_value(
                    "max(docgrid_embedding_claimable_jobs)"
                ) or 0) >= 1
                and (environment.prometheus_value(
                    "max(docgrid_embedding_oldest_claimable_age_seconds)"
                ) or 0) > 300
            ) else None,
            60,
        )
        unavailable_firing_at = wait_for(
            "DocGridEmbeddingWorkersUnavailable firing state",
            lambda: utc_now() if environment.prometheus_alert(
                "DocGridEmbeddingWorkersUnavailable", "firing"
            ) else None,
            150,
            2,
        )
        unavailable_delivery = wait_for(
            "workers unavailable firing webhook",
            lambda: environment.webhook_delivery(
                "DocGridEmbeddingWorkersUnavailable", "firing"
            ),
            60,
        )
        stalled_firing_at = wait_for(
            "DocGridEmbeddingQueueStalled production firing state",
            lambda: utc_now() if environment.prometheus_alert(
                "DocGridEmbeddingQueueStalled", "firing"
            ) else None,
            390,
            5,
        )
        stalled_delivery = wait_for(
            "queue stalled firing webhook",
            lambda: environment.webhook_delivery("DocGridEmbeddingQueueStalled", "firing"),
            75,
        )

        print("Starting a real indexing Worker to drain the Queue...", flush=True)
        recovery_started = utc_now()
        environment.start_backend(
            jar_path, "worker", worker_enabled=True, snapshot_interval="2s"
        )
        indexed_at = wait_for(
            "the queued Embedding Job to reach INDEXED",
            lambda: utc_now() if environment.psql(
                f"SELECT status FROM embedding_jobs WHERE id={job_id}"
            ) == "INDEXED" else None,
            300,
            2,
        )
        embedding_count = int(environment.psql(
            "SELECT COUNT(*) FROM embeddings WHERE document_version_id="
            f"{int(upload['documentVersionId'])}"
        ))
        if embedding_count <= 0:
            raise AssertionError("Worker marked the Job INDEXED without stored embeddings")
        wait_for(
            "observer gauges to reflect Queue recovery",
            lambda: (
                True if metric_value(
                    fetch_metrics(observer), "docgrid_embedding_claimable_jobs"
                ) == 0 else False
            ),
            30,
        )
        wait_for(
            "both Queue alerts to resolve in Prometheus",
            lambda: (
                not environment.prometheus_alert("DocGridEmbeddingWorkersUnavailable")
                and not environment.prometheus_alert("DocGridEmbeddingQueueStalled")
            ),
            60,
        )
        unavailable_resolved = wait_for(
            "workers unavailable resolved webhook",
            lambda: environment.webhook_delivery(
                "DocGridEmbeddingWorkersUnavailable", "resolved"
            ),
            90,
        )
        stalled_resolved = wait_for(
            "queue stalled resolved webhook",
            lambda: environment.webhook_delivery("DocGridEmbeddingQueueStalled", "resolved"),
            90,
        )

        unavailable_received = datetime.fromisoformat(unavailable_delivery["receivedAt"])
        stalled_received = datetime.fromisoformat(stalled_delivery["receivedAt"])
        unavailable_resolved_at = datetime.fromisoformat(unavailable_resolved["receivedAt"])
        stalled_resolved_at = datetime.fromisoformat(stalled_resolved["receivedAt"])
        result = base_result("queue-recovery")
        result["conditions"] = {
            "queueAgeAtStart": ">10m",
            "scrapeInterval": "15s",
            "evaluationInterval": "15s",
            "workersUnavailableRuleFor": "1m",
            "queueStalledRuleFor": "5m",
            "criticalGroupWait": "10s",
            "warningGroupWait": "30s",
            "testGroupInterval": "30s",
            "productionGroupInterval": "5m",
        }
        result["timestamps"] = {
            "conditionObservedAt": iso(condition_at),
            "workersUnavailableFiringAt": iso(unavailable_firing_at),
            "workersUnavailableWebhookAt": iso(unavailable_received),
            "queueStalledFiringAt": iso(stalled_firing_at),
            "queueStalledWebhookAt": iso(stalled_received),
            "recoveryStartedAt": iso(recovery_started),
            "jobIndexedAt": iso(indexed_at),
            "workersUnavailableResolvedWebhookAt": iso(unavailable_resolved_at),
            "queueStalledResolvedWebhookAt": iso(stalled_resolved_at),
        }
        result["measurements"] = {
            "conditionToWorkersUnavailableFiringSeconds": round(
                (unavailable_firing_at - condition_at).total_seconds(), 3
            ),
            "conditionToWorkersUnavailableWebhookSeconds": round(
                (unavailable_received - condition_at).total_seconds(), 3
            ),
            "conditionToQueueStalledFiringSeconds": round(
                (stalled_firing_at - condition_at).total_seconds(), 3
            ),
            "conditionToQueueStalledWebhookSeconds": round(
                (stalled_received - condition_at).total_seconds(), 3
            ),
            "workerStartToIndexedSeconds": round(
                (indexed_at - recovery_started).total_seconds(), 3
            ),
            "workerStartToAllResolvedWebhooksSeconds": round(max(
                (unavailable_resolved_at - recovery_started).total_seconds(),
                (stalled_resolved_at - recovery_started).total_seconds(),
            ), 3),
            "storedEmbeddings": embedding_count,
            "finalJobStatus": "INDEXED",
        }
        return result
    finally:
        environment.close()


def write_result(output_dir, result):
    """Write stable, reviewable JSON evidence for one completed scenario."""
    path = output_dir / f"{result['scenario']}.json"
    path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{result['scenario']}: PASS -> {path}", flush=True)
    return path


def parse_arguments():
    """Expose each experiment independently while retaining one sequential all command."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "scenario",
        choices=["scrape-load", "queue-recovery", "provider-outage", "all"],
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=ROOT_DIR / "backend" / "build" / "reports" / "observability-drill"
        / datetime.now().strftime("%Y%m%d-%H%M%S"),
    )
    return parser.parse_args()


def main():
    """Build only when required, run selected isolated scenarios, and preserve failure diagnostics."""
    arguments = parse_arguments()
    output_dir = arguments.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    scenarios = (
        ["scrape-load", "queue-recovery", "provider-outage"]
        if arguments.scenario == "all" else [arguments.scenario]
    )
    jar_path = build_backend() if any(
        scenario in {"scrape-load", "queue-recovery"} for scenario in scenarios
    ) else None
    results = []
    try:
        for scenario in scenarios:
            if scenario == "scrape-load":
                result = run_scrape_load(output_dir, jar_path)
            elif scenario == "queue-recovery":
                result = run_queue_recovery(output_dir, jar_path)
            else:
                result = run_provider_outage(output_dir)
            write_result(output_dir, result)
            results.append(result)
    except Exception as error:
        failure = {
            "status": "FAIL",
            "scenario": scenario,
            "failedAt": iso(),
            "error": str(error),
            "traceback": traceback.format_exc(),
        }
        (output_dir / f"{scenario}-failure.json").write_text(
            json.dumps(failure, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"{scenario}: FAIL -> {error}", file=sys.stderr, flush=True)
        return 1

    if len(results) > 1:
        summary = {
            "status": "PASS",
            "measuredAt": iso(),
            "scenarios": [result["scenario"] for result in results],
            "resultFiles": [f"{result['scenario']}.json" for result in results],
        }
        (output_dir / "summary.json").write_text(
            json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
    print(f"Observability incident drill completed: {output_dir}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
