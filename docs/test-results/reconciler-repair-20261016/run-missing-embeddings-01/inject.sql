-- run-missing-embeddings-01 주입: 시험 문서 A(version 3)의 활성 모델 Vector 1행(chunk_index 2)을 삭제한다.
-- 정확히 1행만 지워지지 않으면 전체를 롤백한다. 검색·인용 이력이 없는 버전이라 FK 위반이 없다.
BEGIN;
DO $$
DECLARE deleted_count integer;
BEGIN
  DELETE FROM embeddings e
  USING document_chunks c
  WHERE e.chunk_id = c.id
    AND e.document_version_id = 3
    AND c.chunk_index = 2
    AND e.embedding_model_id = (SELECT id FROM embedding_models WHERE is_active)
    AND e.status = 'ACTIVE';
  GET DIAGNOSTICS deleted_count = ROW_COUNT;
  IF deleted_count <> 1 THEN
    RAISE EXCEPTION 'expected 1 deleted embedding, got %', deleted_count;
  END IF;
  RAISE NOTICE 'deleted embeddings: %', deleted_count;
END $$;
COMMIT;
