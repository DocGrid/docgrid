-- NULL인 기존 답변은 출처를 증명할 수 없으므로 재조회·문맥 사용 시 보수적으로 보류한다.
-- 문서가 삭제돼도 출처 ID가 사라지지 않도록 답변 자체에 전이적 문서 목록을 보관한다.
ALTER TABLE rag_responses ADD COLUMN source_document_ids_json TEXT;
