#!/bin/bash
# 사용법: start_backend.sh <phase-name> <worker true|false> <dispatcher true|false>
SP=<scratchpad>
set -a; . $SP/env/test.env; set +a
cd $SP/app || exit 1
exec env -i PATH=/usr/bin:/bin HOME=$HOME TZ=Asia/Seoul \
  SPRING_PROFILES_ACTIVE=local \
  DB_HOST=127.0.0.1 DB_PORT=55471 DB_NAME=app DB_USER=app DB_PASSWORD="$DB_PASSWORD" DB_SSLMODE=disable \
  REDIS_HOST=127.0.0.1 REDIS_PORT=6391 \
  JWT_SECRET="$JWT_SECRET" \
  STORAGE_TYPE=local STORAGE_LOCAL_ROOT=$SP/storage \
  EMBEDDING_SERVER_URL=http://127.0.0.1:18170 \
  OLLAMA_SERVER_URL=http://127.0.0.1:9 \
  INDEXING_WORKER_ENABLED=$2 SYNC_DISPATCHER_ENABLED=$3 SYNC_RECONCILIATION_ENABLED=false \
  INDEXING_WORKER_NAME=rc517-worker SYNC_DISPATCHER_NAME=rc517-dispatcher \
  /usr/bin/java -jar $SP/app/docgrid-f238caf.jar --server.port=18190 --management.server.port=18191 \
  > $SP/logs/backend-$1.log 2>&1
