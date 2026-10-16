#!/bin/bash
# 격리 DB·Valkey로 CI와 같은 backend build를 실행한다. 사용법: run_fulltest.sh <evidence-dir>
SP=<scratchpad>
R=<repo>
set -a; . $SP/env/fulltest.env; set +a
cd $R || exit 1
date '+%Y-%m-%dT%H:%M:%S%z' > $1/started_at.txt
env -i PATH=/usr/bin:/bin HOME=$HOME TZ=Asia/Seoul \
  DB_HOST=127.0.0.1 DB_PORT=55481 DB_USER=app DB_PASSWORD="$DB_PASSWORD" DB_NAME=app \
  REDIS_HOST=127.0.0.1 REDIS_PORT=6401 JWT_SECRET="$JWT_SECRET" SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=3 \
  ./backend/gradlew -p backend clean build --no-daemon --console=plain > $1/live.log 2>&1
echo "exit=$?" > $1/exit_code.txt
date '+%Y-%m-%dT%H:%M:%S%z' > $1/finished_at.txt
