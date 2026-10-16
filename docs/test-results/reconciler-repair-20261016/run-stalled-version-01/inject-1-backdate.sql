-- run-stalled-version-01 주입 1: 시험 문서 D(version 6, UPLOADED, PENDING Job 있음)의 마지막 갱신 시각을
-- 앱이 기록한 값 기준 20분 전으로 옮긴다. 기본 정체 임계값 15분을 넘기기 위한 것이며 설정은 바꾸지 않는다.
-- DB 컨테이너는 UTC이고 앱은 Asia/Seoul LocalDateTime을 쓰므로 now()가 아니라 기존 값에서 뺀다.
BEGIN;
DO $$
DECLARE updated_count integer;
BEGIN
  UPDATE document_versions SET updated_at = updated_at - interval '20 minutes'
  WHERE id = 6 AND status = 'UPLOADED';
  GET DIAGNOSTICS updated_count = ROW_COUNT;
  IF updated_count <> 1 THEN
    RAISE EXCEPTION 'expected 1 updated version, got %', updated_count;
  END IF;
  RAISE NOTICE 'backdated versions: %', updated_count;
END $$;
COMMIT;
