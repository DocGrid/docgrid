#!/bin/bash
# 사용법: start_backend518.sh <phase-name> <worker true|false> <dispatcher true|false> <jar 파일명>
SP=<scratchpad>
set -a; . $SP/env/live518.env; set +a
cd $SP/app || exit 1
exec env -i PATH=/usr/bin:/bin HOME=$HOME TZ=Asia/Seoul \
  SPRING_PROFILES_ACTIVE=local \
  DB_HOST=127.0.0.1 DB_PORT=55472 DB_NAME=app DB_USER=app DB_PASSWORD="$DB_PASSWORD" DB_SSLMODE=disable \
  REDIS_HOST=127.0.0.1 REDIS_PORT=6392 \
  JWT_SECRET="$JWT_SECRET" \
  STORAGE_TYPE=local STORAGE_LOCAL_ROOT=$SP/storage518 \
  EMBEDDING_SERVER_URL=http://127.0.0.1:18171 \
  OLLAMA_SERVER_URL=http://127.0.0.1:9 \
  INDEXING_WORKER_ENABLED=$2 SYNC_DISPATCHER_ENABLED=$3 SYNC_RECONCILIATION_ENABLED=false \
  INDEXING_WORKER_NAME=rc518-worker SYNC_DISPATCHER_NAME=rc518-dispatcher \
  /usr/bin/java -jar $SP/app/$4 --server.port=18192 --management.server.port=18193 \
  > $SP/logs/backend518-$1.log 2>&1
