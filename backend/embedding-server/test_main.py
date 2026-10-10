import threading
import time
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient

import main as embedding_server


@pytest.fixture(autouse=True)
def reset_model():
    """각 계약 테스트가 모델 전역 상태를 공유하지 않게 격리한다."""
    embedding_server.model = None
    embedding_server.provider_admission_controller = (
        embedding_server.ProviderAdmissionController(1, 1, 0.5)
    )
    yield
    embedding_server.model = None


@pytest.fixture
def client():
    return TestClient(embedding_server.app)


def test_liveness_stays_available_while_readiness_reports_model_unavailable(client):
    live_response = client.get("/health/live")
    ready_response = client.get("/health/ready")
    compatibility_response = client.get("/health")

    assert live_response.status_code == 200
    assert live_response.json()["status"] == "alive"
    assert live_response.json()["model"] is None
    assert live_response.json()["uptime_seconds"] >= 0
    assert ready_response.status_code == 503
    assert compatibility_response.status_code == 503


def test_readiness_reports_model_and_admission_snapshot(client):
    embedding_server.model = Mock()
    embedding_server.provider_admission_controller = (
        embedding_server.ProviderAdmissionController(2, 3, 0.5)
    )

    response = client.get("/health/ready")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ready",
        "uptime_seconds": response.json()["uptime_seconds"],
        "model": "BAAI/bge-m3",
        "admission": {
            "active_requests": 0,
            "waiting_requests": 0,
            "max_concurrency": 2,
            "max_queue_size": 3,
            "saturated": False,
        },
    }


def test_compatibility_health_preserves_existing_response_contract(client):
    embedding_server.model = Mock()

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_lifespan_propagates_model_load_failure(monkeypatch):
    model_factory = Mock(side_effect=RuntimeError("model load failure"))
    monkeypatch.setattr(embedding_server, "BGEM3FlagModel", model_factory)

    with pytest.raises(RuntimeError, match="model load failure"):
        with TestClient(embedding_server.app):
            pass

    model_factory.assert_called_once_with("BAAI/bge-m3", use_fp16=True)


def test_metrics_exposes_request_admission_memory_and_process_series(client):
    fake_model = Mock()
    fake_model.encode.return_value = {"dense_vecs": [[0.1, 0.2]]}
    embedding_server.model = fake_model

    assert client.post("/embed", json={"text": "검색어"}).status_code == 200
    response = client.get("/metrics")

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/plain")
    assert "embedding_provider_requests_total" in response.text
    assert 'operation="single"' in response.text
    assert 'outcome="success"' in response.text
    assert "embedding_provider_request_duration_seconds_bucket" in response.text
    assert "embedding_provider_queue_wait_seconds_bucket" in response.text
    assert "embedding_provider_active_requests" in response.text
    assert "embedding_provider_waiting_requests" in response.text
    assert "embedding_provider_memory_limit_bytes" in response.text
    assert "process_resident_memory_bytes" in response.text


def test_metrics_restricts_only_metrics_to_configured_observer(monkeypatch):
    monkeypatch.setenv("EMBEDDING_METRICS_ALLOWED_CLIENTS", "observer-test")
    embedding_server.model = Mock()

    app_client = TestClient(embedding_server.app)
    assert app_client.get("/metrics").status_code == 403
    assert app_client.get("/health").status_code == 200

    monkeypatch.setenv("EMBEDDING_METRICS_ALLOWED_CLIENTS", "testclient")
    assert app_client.get("/metrics").status_code == 200


@pytest.mark.parametrize(
    ("raw_limit", "expected"),
    [
        ("3221225472", 3221225472),
        ("max", 0),
        (str(2**60), 0),
        ("invalid", 0),
    ],
)
def test_cgroup_memory_limit_normalizes_runtime_values(
    tmp_path,
    monkeypatch,
    raw_limit,
    expected,
):
    limit_path = tmp_path / "memory.max"
    limit_path.write_text(raw_limit, encoding="utf-8")
    monkeypatch.setattr(
        embedding_server,
        "CGROUP_MEMORY_LIMIT_PATHS",
        (limit_path,),
    )

    assert embedding_server._cgroup_memory_limit_bytes() == expected


def test_embed_preserves_single_response_contract(client):
    fake_model = Mock()
    fake_model.encode.return_value = {"dense_vecs": [[0.1, 0.2]]}
    embedding_server.model = fake_model

    response = client.post("/embed", json={"text": "검색어"})

    assert response.status_code == 200
    assert response.json() == {"vector": [0.1, 0.2]}
    fake_model.encode.assert_called_once_with(["검색어"], batch_size=1, max_length=8192)


def test_embed_batch_preserves_input_order_and_indexes(client):
    fake_model = Mock()
    fake_model.encode.return_value = {"dense_vecs": [[0.1, 0.2], [0.3, 0.4]]}
    embedding_server.model = fake_model

    response = client.post(
        "/embed/batch",
        json={"texts": ["첫 번째", "두 번째"], "batch_size": 16},
    )

    assert response.status_code == 200
    assert response.json() == {
        "model": "BAAI/bge-m3",
        "embeddings": [
            {"index": 0, "vector": [0.1, 0.2]},
            {"index": 1, "vector": [0.3, 0.4]},
        ],
    }
    fake_model.encode.assert_called_once_with(
        ["첫 번째", "두 번째"], batch_size=16, max_length=8192
    )


@pytest.mark.parametrize(
    "payload",
    [
        {"texts": [], "batch_size": 16},
        {"texts": ["   "], "batch_size": 16},
        {"texts": ["본문"], "batch_size": 0},
        {"texts": ["본문"], "batch_size": 65},
    ],
)
def test_embed_batch_rejects_invalid_request(client, payload):
    embedding_server.model = Mock()

    response = client.post("/embed/batch", json=payload)

    assert response.status_code == 422
    embedding_server.model.encode.assert_not_called()


def test_embed_batch_returns_503_when_model_is_not_loaded(client):
    response = client.post(
        "/embed/batch",
        json={"texts": ["본문"], "batch_size": 1},
    )

    assert response.status_code == 503
    assert response.json() == {"detail": "Model not loaded"}


@pytest.mark.parametrize(
    "provider_result",
    [
        {},
        {"dense_vecs": [[0.1, 0.2]]},
    ],
)
def test_embed_batch_rejects_invalid_provider_result(client, provider_result):
    fake_model = Mock()
    fake_model.encode.return_value = provider_result
    embedding_server.model = fake_model

    response = client.post(
        "/embed/batch",
        json={"texts": ["첫 번째", "두 번째"], "batch_size": 2},
    )

    assert response.status_code == 500
    assert response.json() == {"detail": "Embedding generation failed"}


def test_provider_serializes_model_execution_and_rejects_request_beyond_queue(client):
    """실행 1건·대기 1건 뒤의 요청은 즉시 거절되고 모델 실행은 겹치지 않는다."""
    fake_model = BlockingModel()
    embedding_server.model = fake_model

    with ThreadPoolExecutor(max_workers=2) as executor:
        first = executor.submit(post_batch, client, "첫 번째")
        assert fake_model.started.wait(timeout=1)

        second = executor.submit(post_batch, client, "두 번째")
        wait_until(lambda: embedding_server.provider_admission_controller.waiting_count() == 1)

        started_at = time.perf_counter()
        rejected = post_batch(client, "세 번째")
        rejection_seconds = time.perf_counter() - started_at

        fake_model.release.set()
        accepted = [first.result(timeout=1), second.result(timeout=1)]

    assert [response.status_code for response in accepted] == [200, 200]
    assert rejected.status_code == 429
    assert rejected.json() == {
        "detail": {
            "code": "EMBEDDING_PROVIDER_OVERLOADED",
            "message": "Embedding provider is overloaded",
        }
    }
    assert rejected.headers["Retry-After"] == "1"
    assert rejection_seconds < 0.2
    assert fake_model.max_active == 1
    metrics = client.get("/metrics").text
    assert 'outcome="overloaded"' in metrics
    assert 'outcome="queue_full"' in metrics


def test_provider_returns_429_when_queued_request_waits_too_long(client):
    """Queue 자리가 있어도 permit 대기 예산을 넘으면 HTTP timeout 전에 과부하로 종료한다."""
    embedding_server.provider_admission_controller = (
        embedding_server.ProviderAdmissionController(1, 1, 0.05)
    )
    fake_model = BlockingModel()
    embedding_server.model = fake_model

    with ThreadPoolExecutor(max_workers=1) as executor:
        first = executor.submit(post_batch, client, "실행 중")
        assert fake_model.started.wait(timeout=1)

        started_at = time.perf_counter()
        rejected = post_batch(client, "대기 초과")
        wait_seconds = time.perf_counter() - started_at
        fake_model.release.set()
        assert first.result(timeout=1).status_code == 200

    recovered = post_batch(client, "대기 복구")

    assert rejected.status_code == 429
    assert 0.04 <= wait_seconds < 0.3
    assert recovered.status_code == 200
    assert embedding_server.provider_admission_controller.waiting_count() == 0


def test_provider_releases_permit_after_model_failure(client):
    """모델 예외가 발생해도 다음 요청이 남은 permit을 정상적으로 획득한다."""
    fake_model = Mock()
    fake_model.encode.side_effect = [
        RuntimeError("failure"),
        {"dense_vecs": [[0.1, 0.2]]},
    ]
    embedding_server.model = fake_model

    failed = post_batch(client, "실패")
    recovered = post_batch(client, "복구")

    assert failed.status_code == 500
    assert recovered.status_code == 200
    assert fake_model.encode.call_count == 2


def test_provider_admits_waiting_requests_in_fifo_order():
    """먼저 대기한 요청이 뒤 요청보다 먼저 반환된 permit을 획득한다."""
    controller = embedding_server.ProviderAdmissionController(1, 2, 1.0)
    entered = {name: threading.Event() for name in ("first", "second", "third")}
    releases = {name: threading.Event() for name in ("first", "second", "third")}
    order = []

    def run(name):
        with controller.admission():
            order.append(name)
            entered[name].set()
            assert releases[name].wait(timeout=1)

    with ThreadPoolExecutor(max_workers=3) as executor:
        futures = [executor.submit(run, "first")]
        assert entered["first"].wait(timeout=1)

        futures.append(executor.submit(run, "second"))
        wait_until(lambda: controller.waiting_count() == 1)
        futures.append(executor.submit(run, "third"))
        wait_until(lambda: controller.waiting_count() == 2)

        releases["first"].set()
        assert entered["second"].wait(timeout=1)
        assert entered["third"].is_set() is False
        releases["second"].set()
        assert entered["third"].wait(timeout=1)
        releases["third"].set()

        for future in futures:
            future.result(timeout=1)

    assert order == ["first", "second", "third"]


def post_batch(client, text):
    return client.post(
        "/embed/batch",
        json={"texts": [text], "batch_size": 1},
    )


def wait_until(condition):
    deadline = time.monotonic() + 1
    while time.monotonic() < deadline:
        if condition():
            return
        time.sleep(0.005)
    raise AssertionError("condition was not satisfied before timeout")


class BlockingModel:
    """Test가 해제할 때까지 encode를 점유하며 관측된 최대 동시 실행 수를 기록한다."""

    def __init__(self):
        self.started = threading.Event()
        self.release = threading.Event()
        self.lock = threading.Lock()
        self.active = 0
        self.max_active = 0

    def encode(self, texts, batch_size, max_length):
        with self.lock:
            self.active += 1
            self.max_active = max(self.max_active, self.active)
            self.started.set()
        try:
            assert self.release.wait(timeout=2)
            return {"dense_vecs": [[0.1, 0.2] for _ in texts]}
        finally:
            with self.lock:
                self.active -= 1
