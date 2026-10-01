#!/usr/bin/env bash
# Start one isolated dashboard publisher against the real OpenSQL and shared Redis paths.
# This script prints and records only allowlisted status; Gradle/Spring output may contain
# private endpoints and must not be captured before redaction.
set -euo pipefail

run_id=${1:?run ID required}
output_dir=${2:?output directory required}
secret_dir=${3:?secret directory required}
publish=${4:-true}

if [[ ! $run_id =~ ^[a-zA-Z0-9_-]{4,64}$ ]]; then
  printf 'invalid_run_id\n' >&2
  exit 2
fi
if [[ $publish != true && $publish != false ]]; then
  printf 'invalid_publish_flag\n' >&2
  exit 2
fi
for name in DOCGRID_PROXY_A_HOST DOCGRID_PROXY_B_HOST DOCGRID_DB_NODE1_HOST \
            DOCGRID_DB_NODE2_HOST DOCGRID_DB_NODE3_HOST DOCGRID_REDIS_HOST; do
  if [[ -z ${!name:-} ]]; then
    printf 'missing_required_host_variable\n' >&2
    exit 2
  fi
done
for file in app.password jwt.secret redis.password; do
  if [[ ! -r $secret_dir/$file || $(stat -c %a "$secret_dir/$file") != 600 ]]; then
    printf 'missing_or_insecure_secret_file\n' >&2
    exit 2
  fi
done
if [[ -e $output_dir ]]; then
  printf 'output_already_exists\n' >&2
  exit 2
fi

# 1. Read secrets only into this process environment; never pass them as CLI arguments.
export OPENSQL_APP_PASSWORD JWT_SECRET REDIS_PASSWORD
OPENSQL_APP_PASSWORD=$(head -c 64 "$secret_dir/app.password")
JWT_SECRET=$(head -c 64 "$secret_dir/jwt.secret")
REDIS_PASSWORD=$(head -c 64 "$secret_dir/redis.password")
export OPENSQL_APP_USER=docgrid_app
export REDIS_HOST=$DOCGRID_REDIS_HOST
export REDIS_PORT=6379
export SPRING_PROFILES_ACTIVE=opensql-ha
export SPRING_FLYWAY_ENABLED=false
export MANAGEMENT_SERVER_PORT=8081
export INDEXING_WORKER_ENABLED=false
export SYNC_DISPATCHER_ENABLED=false

# 2. App traffic enters either OpenProxy; disabled Flyway retains its separate primary URL.
export OPENSQL_APP_JDBC_URL="jdbc:postgresql://${DOCGRID_PROXY_A_HOST}:6432,${DOCGRID_PROXY_B_HOST}:6432/docgrid?currentSchema=public&sslmode=disable&connectTimeout=3&socketTimeout=15&loadBalanceHosts=true"
export OPENSQL_MIGRATION_JDBC_URL="jdbc:postgresql://${DOCGRID_DB_NODE1_HOST}:5432,${DOCGRID_DB_NODE2_HOST}:5432,${DOCGRID_DB_NODE3_HOST}:5432/docgrid?targetServerType=primary&connectTimeout=3"
export OPENSQL_MIGRATION_USER=docgrid_migrator
export OPENSQL_MIGRATION_PASSWORD=unused_flyway_disabled

# 3. The fixture itself writes only timestamp, sequence, latency, and exception class.
# Its temporary users and JWT files are removed in its finally block on normal exit.
umask 077
mkdir -m 700 "$output_dir"
start_kst=$(TZ=Asia/Seoul date '+%Y-%m-%dT%H:%M:%S%z')
printf '시작_KST=%s\nrun_id=%s\n기준=OpenSQL_3노드_OpenProxy_2대_공용_Redis\n발행=%s\n' \
  "$start_kst" "$run_id" "$publish" > "$output_dir/launch-status.txt"
set +e
./backend/gradlew -p backend dashboardCloudLoadTest --no-daemon \
  "-Ddashboard.load.runId=$run_id" \
  "-Ddashboard.load.outputDir=$output_dir" \
  "-Ddashboard.load.publish=$publish" \
  '-Ddashboard.load.maxSeconds=1800' >/dev/null 2>&1
exit_code=$?
set -e
printf '종료_KST=%s\nGradle_종료코드=%s\n' \
  "$(TZ=Asia/Seoul date '+%Y-%m-%dT%H:%M:%S%z')" "$exit_code" \
  >> "$output_dir/launch-status.txt"
exit "$exit_code"
