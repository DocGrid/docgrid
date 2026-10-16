# 시험 도우미. source해서 쓴다. 토큰은 scratchpad에만 두고 증거 파일에 남기지 않는다.
SP=<scratchpad>
E=<repo>/docs/test-results/deleted-document-false-positive-20261016
BASE=http://127.0.0.1:18192
q() { docker exec -i docgrid-518-live-pg psql -U app -d app -v ON_ERROR_STOP=1 -P pager=off "$@"; }
qa() { docker exec -i docgrid-518-live-pg psql -U app -d app -v ON_ERROR_STOP=1 -At "$@"; }
login() {
  # V28 시드 ADMIN 계정(시험 DB 전용). 응답 본문은 토큰을 포함하므로 저장하지 않는다.
  curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
    -d "{\"email\":\"kcw130502@gmail.com\",\"password\":\"$(sed -n 's/.*비밀번호: \([^)]*\)).*/\1/p' <repo>/backend/src/main/resources/db/migration/V28__seed_roles_departments_admin.sql)\"}" \
    | python3 -I -c 'import sys,json; d=json.load(sys.stdin)["data"]; print(d.get("accessToken") or d.get("token"))' > $SP/env/token
  chmod 600 $SP/env/token; [ -s $SP/env/token ] && echo login-ok
}
T() { cat $SP/env/token; }
# api METHOD PATH [json-body] -> stdout 응답 JSON
api() {
  if [ -n "$3" ]; then curl -s -X $1 "$BASE$2" -H "Authorization: Bearer $(T)" -H 'Content-Type: application/json' -d "$3";
  else curl -s -X $1 "$BASE$2" -H "Authorization: Bearer $(T)"; fi
}
# reconcile_all MODE OUTDIR LABEL -> cursor를 hasMore=false까지 따라가며 각 응답을 저장
reconcile_all() {
  local mode=$1 out=$2 label=$3 cursor=0 n=0
  mkdir -p $out
  while :; do
    n=$((n+1))
    local f=$out/$label-batch$n.json
    api POST /admin/sync/reconcile "{\"cursor\":$cursor,\"mode\":\"$mode\"}" | python3 -I -m json.tool --no-ensure-ascii > $f
    local more=$(python3 -I -c 'import sys,json; print(json.load(open(sys.argv[1]))["data"]["hasMore"])' $f)
    cursor=$(python3 -I -c 'import sys,json; print(json.load(open(sys.argv[1]))["data"]["endCursor"])' $f)
    python3 -I -c 'import sys,json; d=json.load(open(sys.argv[1]))["data"]; print(sys.argv[2], {k:d[k] for k in ("runId","startCursor","endCursor","scannedCount","detectedCount","repairRequestedCount","hasMore")})' $f "$label-batch$n"
    [ "$more" = "True" ] || break
  done
}
snap_v() { docker exec -i docgrid-518-live-pg psql -U app -d app -v ON_ERROR_STOP=1 -P pager=off -v vid=$1 < <repo>/docs/test-results/deleted-document-false-positive-20261016/sql/snapshot-version.sql; }
snap_g() { docker exec -i docgrid-518-live-pg psql -U app -d app -v ON_ERROR_STOP=1 -P pager=off < <repo>/docs/test-results/deleted-document-false-positive-20261016/sql/snapshot-global.sql; }
