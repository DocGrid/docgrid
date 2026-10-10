import asyncio
import logging
import math
import os
import threading
import time
from collections import deque
from contextlib import asynccontextmanager, contextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request, Response
from FlagEmbedding import BGEM3FlagModel
from prometheus_client import (
    CONTENT_TYPE_LATEST,
    Counter,
    Gauge,
    Histogram,
    generate_latest,
)
from pydantic import BaseModel, Field, field_validator

logger = logging.getLogger(__name__)

MODEL_NAME = "BAAI/bge-m3"
MAX_BATCH_SIZE = 64
OVERLOAD_CODE = "EMBEDDING_PROVIDER_OVERLOADED"
PROCESS_STARTED_AT_SECONDS = time.time()
CGROUP_MEMORY_LIMIT_PATHS = (
    Path("/sys/fs/cgroup/memory.max"),
    Path("/sys/fs/cgroup/memory/memory.limit_in_bytes"),
)
REQUEST_DURATION_BUCKETS = (
    0.005,
    0.01,
    0.025,
    0.05,
    0.1,
    0.25,
    0.5,
    1.0,
    2.5,
    5.0,
    10.0,
    15.0,
    20.0,
    25.0,
    30.0,
    60.0,
    120.0,
)

PROVIDER_REQUESTS_TOTAL = Counter(
    "embedding_provider_requests_total",
    "Embedding Provider requests by operation and bounded outcome.",
    ("operation", "outcome"),
)
PROVIDER_REQUEST_DURATION_SECONDS = Histogram(
    "embedding_provider_request_duration_seconds",
    "Embedding Provider request duration including admission wait.",
    ("operation", "outcome"),
    buckets=REQUEST_DURATION_BUCKETS,
)
PROVIDER_QUEUE_WAIT_SECONDS = Histogram(
    "embedding_provider_queue_wait_seconds",
    "Embedding Provider admission wait duration by outcome.",
    ("outcome",),
    buckets=(0.001, 0.005, 0.01, 0.05, 0.1, 0.25, 0.5, 1.0, 5.0, 15.0, 30.0),
)
PROVIDER_ACTIVE_REQUESTS = Gauge(
    "embedding_provider_active_requests",
    "Current model executions holding an admission permit.",
)
PROVIDER_WAITING_REQUESTS = Gauge(
    "embedding_provider_waiting_requests",
    "Current requests waiting in the bounded FIFO queue.",
)
PROVIDER_MAX_CONCURRENCY_GAUGE = Gauge(
    "embedding_provider_max_concurrency",
    "Configured maximum concurrent model executions.",
)
PROVIDER_MAX_QUEUE_SIZE_GAUGE = Gauge(
    "embedding_provider_max_queue_size",
    "Configured maximum FIFO queue size.",
)
PROVIDER_MODEL_LOADED = Gauge(
    "embedding_provider_model_loaded",
    "Whether the embedding model is loaded and ready, represented as 0 or 1.",
)
PROVIDER_MEMORY_LIMIT_BYTES = Gauge(
    "embedding_provider_memory_limit_bytes",
    "Container cgroup memory limit in bytes, or 0 when unlimited or unavailable.",
)


def _positive_int_environment(name: str, default: int) -> int:
    """환경 변수에서 양의 정수를 읽고 시작 전에 잘못된 안전 설정을 거부한다."""
    value = int(os.getenv(name, str(default)))
    if value < 1:
        raise ValueError(f"{name} must be positive")
    return value


def _non_negative_int_environment(name: str, default: int) -> int:
    """환경 변수에서 0 이상의 정수를 읽어 대기열 비활성화도 명시적으로 허용한다."""
    value = int(os.getenv(name, str(default)))
    if value < 0:
        raise ValueError(f"{name} must not be negative")
    return value


def _positive_float_environment(name: str, default: float) -> float:
    """환경 변수에서 양의 실수를 읽고 무제한 대기 설정을 차단한다."""
    value = float(os.getenv(name, str(default)))
    if not math.isfinite(value) or value <= 0:
        raise ValueError(f"{name} must be positive")
    return value


PROVIDER_MAX_CONCURRENCY = _positive_int_environment(
    "EMBEDDING_PROVIDER_MAX_CONCURRENCY", 1
)
PROVIDER_MAX_QUEUE_SIZE = _non_negative_int_environment(
    "EMBEDDING_PROVIDER_MAX_QUEUE_SIZE", 1
)
PROVIDER_QUEUE_WAIT_TIMEOUT_SECONDS = _positive_float_environment(
    "EMBEDDING_PROVIDER_QUEUE_WAIT_TIMEOUT_SECONDS", 15.0
)


class ProviderOverloadedError(RuntimeError):
    """Provider 실행 permit을 bounded queue 정책 안에서 얻지 못한 과부하를 표현한다."""

    def __init__(
        self,
        reason: str,
        retry_after_seconds: float,
        wait_seconds: float,
    ):
        super().__init__(reason)
        self.retry_after_seconds = retry_after_seconds
        self.wait_seconds = wait_seconds


class ProviderAdmissionController:
    """한 Provider 프로세스의 모델 실행 동시성과 대기 요청 수를 함께 제한한다.

    모델 실행 permit만 소유하며 HTTP 응답과 모델 결과 계약에는 관여하지 않는다. 현재 Docker
    구성처럼 Uvicorn 단일 프로세스에서 컨테이너 전체 제한으로 동작한다.
    """

    def __init__(
        self,
        max_concurrency: int,
        max_queue_size: int,
        queue_wait_timeout_seconds: float,
    ):
        if max_concurrency < 1 or max_queue_size < 0 or queue_wait_timeout_seconds <= 0:
            raise ValueError("provider admission settings are invalid")
        self._max_concurrency = max_concurrency
        self._available_permits = max_concurrency
        self._max_queue_size = max_queue_size
        self._queue_wait_timeout_seconds = queue_wait_timeout_seconds
        self._condition = threading.Condition()
        self._waiters: deque[object] = deque()

    @contextmanager
    def admission(self):
        """즉시 permit 또는 제한된 대기 자리를 확보하고 종료 시 permit을 반드시 반환한다."""
        started_at = time.monotonic()
        with self._condition:
            # 1. 기존 Waiter가 없을 때만 즉시 permit을 주어 새 요청의 Queue 추월을 차단한다.
            if self._available_permits > 0 and not self._waiters:
                self._available_permits -= 1
            else:
                if len(self._waiters) >= self._max_queue_size:
                    raise ProviderOverloadedError(
                        "queue_full",
                        self._queue_wait_timeout_seconds,
                        time.monotonic() - started_at,
                    )
                waiter = object()
                self._waiters.append(waiter)
                deadline = time.monotonic() + self._queue_wait_timeout_seconds

                # 2. Queue 선두만 반환된 permit을 얻도록 Condition 안에서 FIFO 순서를 확인한다.
                while self._waiters[0] is not waiter or self._available_permits == 0:
                    remaining_seconds = deadline - time.monotonic()
                    if remaining_seconds <= 0:
                        self._waiters.remove(waiter)
                        self._condition.notify_all()
                        raise ProviderOverloadedError(
                            "queue_wait_timeout",
                            self._queue_wait_timeout_seconds,
                            time.monotonic() - started_at,
                        )
                    self._condition.wait(timeout=remaining_seconds)

                self._waiters.popleft()
                self._available_permits -= 1

        try:
            # 3. 실제 모델 실행 구간만 permit으로 보호해 응답 직렬화 비용은 포함하지 않는다.
            yield time.monotonic() - started_at
        finally:
            with self._condition:
                self._available_permits += 1
                self._condition.notify_all()

    def waiting_count(self) -> int:
        """동시성 계약 테스트와 진단을 위해 현재 대기 요청 수의 일관된 Snapshot을 반환한다."""
        return self.snapshot()["waiting_requests"]

    def snapshot(self) -> dict[str, int | bool]:
        """Health와 Metric이 같은 시점의 실행·대기·상한 상태를 사용하도록 Snapshot을 반환한다."""
        with self._condition:
            active_requests = self._max_concurrency - self._available_permits
            waiting_requests = len(self._waiters)
            return {
                "active_requests": active_requests,
                "waiting_requests": waiting_requests,
                "max_concurrency": self._max_concurrency,
                "max_queue_size": self._max_queue_size,
                "saturated": active_requests >= self._max_concurrency
                and waiting_requests >= self._max_queue_size,
            }


model: BGEM3FlagModel | None = None
provider_admission_controller = ProviderAdmissionController(
    PROVIDER_MAX_CONCURRENCY,
    PROVIDER_MAX_QUEUE_SIZE,
    PROVIDER_QUEUE_WAIT_TIMEOUT_SECONDS,
)


def _cgroup_memory_limit_bytes() -> int:
    """Cgroup v2·v1 Memory Limit을 읽고 무제한 또는 읽기 실패는 0으로 정규화한다."""
    for path in CGROUP_MEMORY_LIMIT_PATHS:
        try:
            raw_value = path.read_text(encoding="utf-8").strip()
            if raw_value == "max":
                return 0
            limit_bytes = int(raw_value)
            # Cgroup v1은 사실상 무제한을 매우 큰 정수로 표현한다.
            return 0 if limit_bytes <= 0 or limit_bytes >= 2**60 else limit_bytes
        except (OSError, ValueError):
            continue
    return 0


PROVIDER_ACTIVE_REQUESTS.set_function(
    lambda: provider_admission_controller.snapshot()["active_requests"]
)
PROVIDER_WAITING_REQUESTS.set_function(
    lambda: provider_admission_controller.snapshot()["waiting_requests"]
)
PROVIDER_MAX_CONCURRENCY_GAUGE.set_function(
    lambda: provider_admission_controller.snapshot()["max_concurrency"]
)
PROVIDER_MAX_QUEUE_SIZE_GAUGE.set_function(
    lambda: provider_admission_controller.snapshot()["max_queue_size"]
)
PROVIDER_MODEL_LOADED.set_function(lambda: 1 if model is not None else 0)
PROVIDER_MEMORY_LIMIT_BYTES.set_function(_cgroup_memory_limit_bytes)


def _load_model():
    global model
    logger.info("Loading %s model...", MODEL_NAME)
    model = BGEM3FlagModel(MODEL_NAME, use_fp16=True)
    logger.info("Model loaded.")


@asynccontextmanager
async def lifespan(app: FastAPI):
    global model
    # 1. Model Load 실패를 Startup 실패로 전파해 Container restart policy가 복구하도록 한다.
    await asyncio.to_thread(_load_model)
    try:
        yield
    finally:
        # 2. 종료 중 새 요청이 준비된 Model을 사용하지 못하도록 전역 참조를 제거한다.
        model = None


app = FastAPI(lifespan=lifespan)


class EmbedRequest(BaseModel):
    text: str


class EmbedResponse(BaseModel):
    vector: list[float]


class EmbedBatchRequest(BaseModel):
    """여러 문서 Chunk의 원문 순서와 모델 내부 Batch 크기를 전달한다."""

    texts: list[str] = Field(min_length=1, max_length=MAX_BATCH_SIZE)
    batch_size: int = Field(ge=1, le=MAX_BATCH_SIZE)

    @field_validator("texts")
    @classmethod
    def validate_texts(cls, texts: list[str]) -> list[str]:
        if any(not text.strip() for text in texts):
            raise ValueError("texts must not contain blank values")
        return texts


class EmbedBatchItemResponse(BaseModel):
    """요청 목록의 위치와 해당 위치에서 생성된 Dense Vector를 결합한다."""

    index: int
    vector: list[float]


class EmbedBatchResponse(BaseModel):
    """실제 사용 모델과 요청 순서를 보존한 Batch Embedding 결과를 반환한다."""

    model: str
    embeddings: list[EmbedBatchItemResponse]


def _encode(
    texts: list[str],
    batch_size: int,
    operation: str,
) -> list[list[float]]:
    """입력 전체를 한 번에 Encoding하고 Dense Vector 개수 계약을 검증한다."""
    started_at = time.monotonic()
    outcome = "error"
    try:
        if model is None:
            outcome = "unavailable"
            raise HTTPException(status_code=503, detail="Model not loaded")

        with provider_admission_controller.admission() as wait_seconds:
            PROVIDER_QUEUE_WAIT_SECONDS.labels(outcome="admitted").observe(wait_seconds)
            result = model.encode(texts, batch_size=batch_size, max_length=8192)
        dense_vectors = result.get("dense_vecs") if isinstance(result, dict) else None
        if dense_vectors is None or len(dense_vectors) != len(texts):
            raise ValueError("dense vector count mismatch")

        # Provider 배열 형식과 상관없이 JSON 직렬화 가능한 float 목록으로 경계를 고정한다.
        vectors = [
            vector.tolist() if hasattr(vector, "tolist") else list(vector)
            for vector in dense_vectors
        ]
        outcome = "success"
        return vectors
    except HTTPException:
        raise
    except ProviderOverloadedError as exception:
        outcome = "overloaded"
        PROVIDER_QUEUE_WAIT_SECONDS.labels(outcome=str(exception)).observe(
            exception.wait_seconds
        )
        logger.warning("Embedding request rejected. reason=%s", str(exception))
        raise HTTPException(
            status_code=429,
            detail={
                "code": OVERLOAD_CODE,
                "message": "Embedding provider is overloaded",
            },
            headers={
                "Retry-After": str(math.ceil(exception.retry_after_seconds))
            },
        ) from exception
    except Exception as exception:
        logger.error("Embedding generation failed. cause=%s", type(exception).__name__)
        raise HTTPException(status_code=500, detail="Embedding generation failed") from exception
    finally:
        PROVIDER_REQUESTS_TOTAL.labels(operation=operation, outcome=outcome).inc()
        PROVIDER_REQUEST_DURATION_SECONDS.labels(
            operation=operation,
            outcome=outcome,
        ).observe(time.monotonic() - started_at)


def _health_payload(status: str) -> dict[str, object]:
    """Health Endpoint가 동일한 Model·Admission Snapshot을 사용하도록 응답을 생성한다."""
    return {
        "status": status,
        "uptime_seconds": round(max(0.0, time.time() - PROCESS_STARTED_AT_SECONDS), 3),
        "model": MODEL_NAME if model is not None else None,
        "admission": provider_admission_controller.snapshot(),
    }


@app.get("/health/live")
def liveness():
    return _health_payload("alive")


@app.get("/health/ready")
def readiness():
    if model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    return _health_payload("ready")


@app.get("/health")
def health():
    if model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    # 기존 Benchmark와 Client가 의존하는 응답 계약은 상세 readiness 도입 뒤에도 보존한다.
    return {"status": "ok"}


@app.get("/metrics", include_in_schema=False)
def metrics(request: Request):
    # 1. 배포 환경은 관측 VM 주소를 명시해 지표만 제한한다. 임베딩 요청은 기존 앱 경로를 유지한다.
    allowed_clients = os.getenv("EMBEDDING_METRICS_ALLOWED_CLIENTS", "")
    if allowed_clients:
        allowed = {client.strip() for client in allowed_clients.split(",") if client.strip()}
        if request.client is None or request.client.host not in allowed:
            raise HTTPException(status_code=403, detail="Metrics access denied")
    # 2. 허용된 관측 클라이언트에만 본문과 지표를 반환한다.
    return Response(content=generate_latest(), media_type=CONTENT_TYPE_LATEST)


@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    vector = _encode([req.text], batch_size=1, operation="single")[0]
    return EmbedResponse(vector=vector)


@app.post("/embed/batch", response_model=EmbedBatchResponse)
def embed_batch(req: EmbedBatchRequest):
    vectors = _encode(req.texts, batch_size=req.batch_size, operation="batch")
    return EmbedBatchResponse(
        model=MODEL_NAME,
        embeddings=[
            EmbedBatchItemResponse(index=index, vector=vector)
            for index, vector in enumerate(vectors)
        ],
    )
