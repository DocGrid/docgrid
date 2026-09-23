#!/usr/bin/env bash
set -euo pipefail

# 공급사의 기존 opensql 풀은 유지하고 DocGrid 전용 DB·계정 풀만 추가한다.
# 이 스크립트는 실행 중인 프록시를 재시작하지 않으므로 적용 후 접속 검증이 필요하다.
secret_file="${OPENSQL_APP_SECRET_FILE:?root-only secret file required}"
config="${OPENSQL_PROXY_CONFIG_FILE:?proxy config path required}"
node1_host="${OPENSQL_NODE1_HOST:?node1 host required}"
node2_host="${OPENSQL_NODE2_HOST:?node2 host required}"
node3_host="${OPENSQL_NODE3_HOST:?node3 host required}"

if [[ "${EUID}" -ne 0 ]]; then
  printf 'root 권한으로 실행해야 합니다.\n' >&2
  exit 1
fi
if [[ ! -f "${config}" || ! -f "${secret_file}" ]]; then
  printf 'OpenProxy 설정 또는 비공개 암호 파일이 없습니다.\n' >&2
  exit 1
fi
for host in "${node1_host}" "${node2_host}" "${node3_host}"; do
  if [[ ! "${host}" =~ ^[[:alnum:].:-]+$ ]]; then
    printf '노드 주소 형식이 올바르지 않습니다.\n' >&2
    exit 1
  fi
done
if grep -q '^\[pools.docgrid\]$' "${config}"; then
  printf 'DocGrid 풀이 이미 있어 중단합니다.\n' >&2
  exit 1
fi

# 1. 암호를 명령 인자와 출력에 노출하지 않고 root 전용 파일에서만 읽는다.
set -a
. "${secret_file}"
set +a
if [[ ! "${DOCGRID_APP_PASSWORD:-}" =~ ^[[:xdigit:]]{64}$ ]]; then
  printf 'DocGrid 앱 암호 형식이 올바르지 않습니다.\n' >&2
  exit 1
fi

# 2. 기존 설정을 복구 가능한 형태로 보관한 뒤 같은 디렉터리에서 원자적으로 교체한다.
backup="${config}.before-docgrid-$(date -u +%Y%m%dT%H%M%SZ)"
cp -p "${config}" "${backup}"
chown root:root "${backup}"
chmod 0600 "${backup}"
temporary="$(mktemp "${config}.docgrid.XXXXXX")"
trap 'rm -f "${temporary}"' EXIT
cat "${config}" > "${temporary}"
printf '\n[pools.docgrid]\n' >> "${temporary}"
printf 'pool_mode = "session"\ndefault_role = "primary"\nquery_parser_enabled = false\n' >> "${temporary}"
printf '\n[pools.docgrid.users.0]\nusername = "docgrid_app"\npassword = "%s"\npool_size = 5\n' \
  "${DOCGRID_APP_PASSWORD}" >> "${temporary}"
printf '\n[pools.docgrid.shards.0]\nservers = [\n' >> "${temporary}"
printf '    ["%s", 5432, "auto"],\n    ["%s", 5432, "auto"],\n    ["%s", 5432, "auto"],\n' \
  "${node1_host}" "${node2_host}" "${node3_host}" >> "${temporary}"
printf ']\ndatabase = "docgrid"\nuse_patroni = true\npatroni_port = "8008"\n' >> "${temporary}"
chown opensql:opensql "${temporary}"
chmod 0600 "${temporary}"
mv "${temporary}" "${config}"
trap - EXIT

printf 'DocGrid OpenProxy 풀 추가 완료 (백업: %s)\n' "${backup}"
