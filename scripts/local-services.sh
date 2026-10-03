#!/usr/bin/env bash
set -Eeuo pipefail

# DocGrid 로컬 실행에 필수인 Valkey, Embedding Provider, Ollama의 기동과 준비 상태만 관리한다.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ACTION="${1:-status}"
WAIT_TIMEOUT_SECONDS="${LOCAL_SERVICES_WAIT_TIMEOUT_SECONDS:-900}"
POLL_INTERVAL_SECONDS="${LOCAL_SERVICES_POLL_INTERVAL_SECONDS:-2}"
EMBEDDING_BASE_URL="${EMBEDDING_SERVER_URL:-http://127.0.0.1:8000}"
OLLAMA_BASE_URL="${OLLAMA_SERVER_URL:-http://127.0.0.1:11434}"
OLLAMA_MODEL_NAME="${OLLAMA_MODEL:-qwen2.5:7b}"
OLLAMA_LOG_FILE="${TMPDIR:-/tmp}/docgrid-ollama.log"

log() {
    printf '[local-services] %s\n' "$*"
}

ok() {
    printf '[OK] %s\n' "$*"
}

fail() {
    printf '[FAIL] %s\n' "$*" >&2
}

warn() {
    printf '[WARN] %s\n' "$*" >&2
}

usage() {
    cat <<'EOF'
Usage: ./scripts/local-services.sh <start|status>

  start   Valkey, Embedding Provider, Ollama를 기동하고 준비 완료까지 기다립니다.
  status  세 서비스의 실제 연결 상태와 Ollama 모델 준비 상태를 즉시 확인합니다.

Environment:
  LOCAL_SERVICES_WAIT_TIMEOUT_SECONDS  start 대기 시간(기본 900초)
  LOCAL_SERVICES_POLL_INTERVAL_SECONDS 점검 주기(기본 2초)
  EMBEDDING_SERVER_URL                 Embedding Provider 주소
  OLLAMA_SERVER_URL                    Ollama 주소
  OLLAMA_MODEL                         필수 Ollama 모델(기본 qwen2.5:7b)
EOF
}

require_positive_integer() {
    local name="$1"
    local value="$2"
    if [[ ! "$value" =~ ^[1-9][0-9]*$ ]]; then
        fail "$name 값은 1 이상의 정수여야 합니다: $value"
        exit 2
    fi
}

valkey_ready() {
    docker compose exec -T valkey valkey-cli ping 2>/dev/null | grep -qx 'PONG'
}

embedding_ready() {
    curl -fsS --max-time 3 "${EMBEDDING_BASE_URL%/}/health/ready" >/dev/null 2>&1
}

ollama_ready() {
    curl -fsS --max-time 3 "${OLLAMA_BASE_URL%/}/api/tags" >/dev/null 2>&1
}

ollama_model_ready() {
    local tags_json
    tags_json="$(curl -fsS --max-time 3 "${OLLAMA_BASE_URL%/}/api/tags")" || return 1

    if command -v python3 >/dev/null 2>&1; then
        printf '%s' "$tags_json" | python3 -c '
import json
import sys

expected = sys.argv[1]
models = json.load(sys.stdin).get("models", [])
sys.exit(0 if any(model.get("name") == expected or model.get("model") == expected for model in models) else 1)
' "$OLLAMA_MODEL_NAME"
        return
    fi

    # Python이 없는 최소 환경에서도 Ollama의 compact JSON 응답은 정확한 모델명으로 확인한다.
    printf '%s' "$tags_json" | grep -Fq "\"name\":\"$OLLAMA_MODEL_NAME\""
}

ollama_runs_in_docker() {
    command -v docker >/dev/null 2>&1 \
        && docker ps --format '{{.Names}}' 2>/dev/null | grep -qx 'docgrid-ollama'
}

legacy_ollama_container_exists() {
    command -v docker >/dev/null 2>&1 \
        && docker inspect docgrid-ollama >/dev/null 2>&1
}

wait_until_ready() {
    local label="$1"
    local check_function="$2"
    local deadline=$((SECONDS + WAIT_TIMEOUT_SECONDS))

    while (( SECONDS < deadline )); do
        if "$check_function"; then
            ok "$label 준비 완료"
            return 0
        fi
        sleep "$POLL_INTERVAL_SECONDS"
    done

    fail "$label 응답을 ${WAIT_TIMEOUT_SECONDS}초 안에 확인하지 못했습니다."
    return 1
}

start_ollama() {
    if ollama_ready; then
        ok "Ollama가 이미 실행 중입니다."
        return 0
    fi
    # Native Ollama는 macOS에서 Homebrew Service를 우선 사용해 Terminal 종료 후에도 유지한다.
    if [[ "$(uname -s)" == "Darwin" ]] \
        && command -v brew >/dev/null 2>&1 \
        && brew list --formula ollama >/dev/null 2>&1; then
        log "Homebrew Service로 Ollama를 시작합니다."
        brew services start ollama >/dev/null
        return 0
    fi

    if [[ "$(uname -s)" == "Darwin" && -d '/Applications/Ollama.app' ]]; then
        log "Ollama 앱을 시작합니다."
        open -a Ollama
        return 0
    fi

    if command -v ollama >/dev/null 2>&1; then
        log "백그라운드 프로세스로 Ollama를 시작합니다. 로그: $OLLAMA_LOG_FILE"
        nohup ollama serve >"$OLLAMA_LOG_FILE" 2>&1 &
        return 0
    fi

    # 이전 Compose에서 생성된 컨테이너는 삭제하지 않고 Native 설치 전 호환 경로로만 재사용한다.
    if legacy_ollama_container_exists; then
        warn "Native Ollama가 없어 기존 docgrid-ollama 컨테이너를 시작합니다. Metal 가속은 사용할 수 없습니다."
        docker start docgrid-ollama >/dev/null
        return 0
    fi

    fail "Native Ollama가 없습니다. macOS에서는 'brew install ollama'로 설치하세요."
    return 1
}

print_status() {
    local failures=0

    # 1. 실행 상태가 아니라 실제 Valkey 명령 응답으로 로그인 캐시 사용 가능 여부를 확인한다.
    if valkey_ready; then
        ok "Valkey 연결 가능"
    else
        fail "Valkey 연결 실패 — 복구: docker compose up -d valkey"
        failures=$((failures + 1))
    fi

    # 2. 모델 로딩까지 끝난 ready Endpoint로 검색용 Embedding Provider를 확인한다.
    if embedding_ready; then
        ok "Embedding Provider 준비 완료 (${EMBEDDING_BASE_URL%/})"
    else
        fail "Embedding Provider 준비 안 됨 — 복구: docker compose up -d embedding-server"
        fail "진행 확인: docker compose logs -f embedding-server"
        failures=$((failures + 1))
    fi

    # 3. Ollama API와 애플리케이션이 요구하는 모델을 분리해 AI 답변 가능 여부를 확인한다.
    if ollama_ready; then
        ok "Ollama API 연결 가능 (${OLLAMA_BASE_URL%/})"
        if ollama_runs_in_docker; then
            warn "Ollama가 Docker 컨테이너로 실행 중입니다. macOS Metal 가속을 위해 Native Ollama 전환을 권장합니다."
        fi
        if ollama_model_ready; then
            ok "Ollama 모델 준비 완료 ($OLLAMA_MODEL_NAME)"
        elif ollama_runs_in_docker; then
            fail "Ollama 모델 없음 — 복구: docker exec docgrid-ollama ollama pull $OLLAMA_MODEL_NAME"
            failures=$((failures + 1))
        else
            fail "Ollama 모델 없음 — 복구: ollama pull $OLLAMA_MODEL_NAME"
            failures=$((failures + 1))
        fi
    else
        fail "Ollama API 연결 실패 — 복구: brew services start ollama 또는 ollama serve"
        failures=$((failures + 1))
    fi

    if (( failures > 0 )); then
        fail "총 ${failures}개 점검이 실패했습니다. 일괄 복구: ./scripts/local-services.sh start"
        return 1
    fi

    ok "Valkey·Embedding Provider·Ollama가 모두 사용 가능합니다."
}

start_services() {
    command -v docker >/dev/null 2>&1 || {
        fail "Docker를 찾을 수 없습니다. Docker Desktop을 설치하고 실행하세요."
        return 1
    }
    command -v curl >/dev/null 2>&1 || {
        fail "curl을 찾을 수 없습니다."
        return 1
    }
    docker info >/dev/null 2>&1 || {
        fail "Docker Engine에 연결할 수 없습니다. Docker Desktop 실행 상태를 확인하세요."
        return 1
    }

    # 1. Compose 서비스는 기존 Volume을 유지한 채 필요한 두 서비스만 멱등하게 기동한다.
    log "Valkey와 Embedding Provider를 시작합니다."
    COMPOSE_IGNORE_ORPHANS=true docker compose up -d valkey embedding-server

    # 2. Native Ollama를 기동해 Docker Desktop의 CPU 추론 경로를 피한다.
    start_ollama

    # 3. 각 서비스가 실제 요청을 받을 수 있을 때까지 제한 시간 안에서 기다린다.
    wait_until_ready "Valkey" valkey_ready
    wait_until_ready "Embedding Provider" embedding_ready
    wait_until_ready "Ollama API" ollama_ready

    # 4. 마지막으로 모델을 포함한 전체 상태를 같은 기준으로 다시 검증한다.
    print_status
}

require_positive_integer "LOCAL_SERVICES_WAIT_TIMEOUT_SECONDS" "$WAIT_TIMEOUT_SECONDS"
require_positive_integer "LOCAL_SERVICES_POLL_INTERVAL_SECONDS" "$POLL_INTERVAL_SECONDS"
cd "$PROJECT_ROOT"

case "$ACTION" in
    start)
        start_services
        ;;
    status)
        print_status
        ;;
    help|-h|--help)
        usage
        ;;
    *)
        usage >&2
        exit 2
        ;;
esac
