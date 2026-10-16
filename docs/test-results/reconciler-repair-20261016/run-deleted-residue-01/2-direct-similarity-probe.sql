-- :q 는 임베딩 서버 POST /embed 로 얻은 질문 Vector 리터럴(1024차원)이다. 값은 길어서 저장하지 않았다.
-- 검색 API를 거치지 않고 같은 질문 Vector와 버전별 ACTIVE Vector의 최대 코사인 유사도를 비교한다(읽기 전용 진단).
SELECT e.document_version_id, d.status AS document_status, max(1 - (e.vector <=> :'q'::vector)) AS max_cosine_similarity, count(*) AS active_vectors
FROM embeddings e JOIN documents d ON d.id = e.document_id
WHERE e.status = 'ACTIVE'
GROUP BY 1, 2 ORDER BY 3 DESC;
