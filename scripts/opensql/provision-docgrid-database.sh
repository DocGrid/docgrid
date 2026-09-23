#!/usr/bin/env bash
set -euo pipefail

# 3노드 검증 클러스터의 현재 리더에서만 DocGrid 전용 DB와 분리된 계정을 한 번 생성한다.
# 기존 DB/계정이 있으면 중단해 자격 증명이나 데이터를 덮어쓰지 않는다.
container="${OPENSQL_CONTAINER_NAME:?container name required}"
credentials="${OPENSQL_DOCGRID_CREDENTIALS_FILE:?root-only credential path required}"
superuser_env="${OPENSQL_SUPERUSER_ENV_FILE:?container credential path required}"
psql_bin="${OPENSQL_PSQL_BIN:?container psql path required}"

if [[ "${EUID}" -ne 0 ]]; then
  printf 'root 권한으로 실행해야 합니다.\n' >&2
  exit 1
fi
if [[ -e "${credentials}" ]]; then
  printf '기존 DocGrid 자격 증명 파일이 있어 중단합니다: %s\n' "${credentials}" >&2
  exit 1
fi

psql_super() {
  local database="$1"
  docker exec -i "${container}" sh -c '
    . "$1"
    export PGPASSWORD="${PG_SUPERUSER_PASSWORD}"
    exec "$2" -h 127.0.0.1 -U postgres -d "$3" -v ON_ERROR_STOP=1 -At
  ' sh "${superuser_env}" "${psql_bin}" "${database}"
}

# 1. 리더와 이름 충돌을 먼저 확인한다.
if [[ "$(printf 'SELECT pg_is_in_recovery();\n' | psql_super postgres)" != "f" ]]; then
  printf '%s은 현재 리더가 아닙니다.\n' "${container}" >&2
  exit 1
fi
if [[ "$(printf "SELECT count(*) FROM pg_database WHERE datname = 'docgrid';\n" | psql_super postgres)" != "0" ]]; then
  printf 'docgrid DB가 이미 있어 중단합니다.\n' >&2
  exit 1
fi
if [[ "$(printf "SELECT count(*) FROM pg_roles WHERE rolname IN ('docgrid_migrator', 'docgrid_app');\n" | psql_super postgres)" != "0" ]]; then
  printf 'DocGrid 계정이 이미 있어 중단합니다.\n' >&2
  exit 1
fi

# 2. 암호는 저장소나 명령 인자가 아닌 root 전용 영속 볼륨에만 보관한다.
umask 077
migration_password="$(openssl rand -hex 32)"
app_password="$(openssl rand -hex 32)"
printf 'DOCGRID_MIGRATION_PASSWORD=%s\nDOCGRID_APP_PASSWORD=%s\n' \
  "${migration_password}" "${app_password}" > "${credentials}"
chmod 0600 "${credentials}"
chown root:root "${credentials}"

# 3. DB 소유자와 런타임 계정을 분리하고 벡터 확장을 준비한다.
printf "CREATE ROLE docgrid_migrator LOGIN PASSWORD '%s';\nCREATE ROLE docgrid_app LOGIN PASSWORD '%s';\nCREATE DATABASE docgrid OWNER docgrid_migrator;\n" \
  "${migration_password}" "${app_password}" | psql_super postgres >/dev/null
printf '%s\n' \
  'CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;' \
  'GRANT CONNECT ON DATABASE docgrid TO docgrid_app;' \
  'GRANT USAGE ON SCHEMA public TO docgrid_app;' \
  'ALTER DEFAULT PRIVILEGES FOR ROLE docgrid_migrator IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO docgrid_app;' \
  'ALTER DEFAULT PRIVILEGES FOR ROLE docgrid_migrator IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO docgrid_app;' \
  | psql_super docgrid >/dev/null

printf 'DocGrid DB와 분리 계정 생성 완료: %s\n' "${container}"
