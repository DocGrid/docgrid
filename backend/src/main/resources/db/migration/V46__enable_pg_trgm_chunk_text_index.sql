-- 하이브리드 검색의 단어 갈래: 질문 단어가 청크에 들어 있는지 정규식(~*)으로 찾는다 (#456).
-- pg_trgm GIN 인덱스는 이 정규식·ILIKE 조회가 청크 전체를 훑지 않고 후보만 읽게 한다.
-- tsvector('simple')는 "고구려는", "@MessageMapping으로"처럼 조사가 붙은 토큰을 통째로 한 단어로 봐서
-- 한국어·영문 식별자가 섞인 문서에서는 일치를 놓치므로 쓰지 않는다.
--
-- pg_trgm은 trusted 확장이라 DB CREATE 권한만 있으면 설치할 수 있다. vector 확장처럼
-- DB 관리자가 미리 설치해 두면 아래 문장은 변경 없이 통과한다.
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

-- 연산자 클래스는 검색 경로(search_path)와 무관하게 public 확장 객체를 가리키도록 스키마를 명시한다.
CREATE INDEX idx_document_chunks_chunk_text_trgm
    ON document_chunks USING gin (chunk_text public.gin_trgm_ops);
