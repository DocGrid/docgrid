#!/bin/bash
# 이번 시험에서 띄운 격리 컨테이너. 비밀번호는 실행마다 openssl rand로 만들고 시험 후 삭제했다.
docker run -d --name docgrid-reconcile-pg-20261016 -p 127.0.0.1:55471:5432 \
  -e POSTGRES_DB=app -e POSTGRES_USER=app -e POSTGRES_PASSWORD="$DB_PASSWORD" \
  -v <repo>/docker/postgres/init/001-enable-vector.sql:/docker-entrypoint-initdb.d/001-enable-vector.sql:ro \
  --label purpose=docgrid-517-reconciler-test pgvector/pgvector:0.8.1-pg17
docker run -d --name docgrid-reconcile-valkey-20261016 -p 127.0.0.1:6391:6379 \
  --label purpose=docgrid-517-reconciler-test valkey/valkey:9.1-alpine
# 모델 파일은 기존 Hugging Face cache volume을 읽기 전용으로 붙이고 네트워크 다운로드를 끈다.
docker run -d --name docgrid-reconcile-embedding-20261016 -p 127.0.0.1:18170:8000 \
  -e HF_HUB_OFFLINE=1 -e TRANSFORMERS_OFFLINE=1 -e EMBEDDING_PROVIDER_MAX_CONCURRENCY=1 -e EMBEDDING_PROVIDER_MAX_QUEUE_SIZE=1 \
  --memory 3g --cpus 4 -v docgrid_huggingface-cache:/root/.cache/huggingface:ro \
  --label purpose=docgrid-517-reconciler-test docgrid-embedding-server
# 정리: docker rm -f -v docgrid-reconcile-pg-20261016 docgrid-reconcile-valkey-20261016 docgrid-reconcile-embedding-20261016
