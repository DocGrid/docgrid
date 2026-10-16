-- run-missing-job-01 주입: Worker가 꺼진 상태에서 시험 문서 B(version 5)의 PENDING Job을 삭제한다.
-- attempt·indexing_event가 하나라도 있으면 FK 문제와 처리 이력 손상을 피하려고 전체를 롤백한다.
BEGIN;
DO $$
DECLARE deleted_count integer;
BEGIN
  IF EXISTS (SELECT 1 FROM embedding_job_attempts a JOIN embedding_jobs j ON j.id = a.embedding_job_id WHERE j.document_version_id = 5)
     OR EXISTS (SELECT 1 FROM indexing_events e JOIN embedding_jobs j ON j.id = e.embedding_job_id WHERE j.document_version_id = 5) THEN
    RAISE EXCEPTION 'job history exists; abort injection';
  END IF;
  DELETE FROM embedding_jobs WHERE document_version_id = 5 AND status = 'PENDING';
  GET DIAGNOSTICS deleted_count = ROW_COUNT;
  IF deleted_count <> 1 THEN
    RAISE EXCEPTION 'expected 1 deleted job, got %', deleted_count;
  END IF;
  RAISE NOTICE 'deleted jobs: %', deleted_count;
END $$;
COMMIT;
