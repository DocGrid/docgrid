package com.opensource.docgrid.domain.rag.service.command;

/**
 * 한 번의 RAG 작업 선점에만 유효한 소유권이다. 재선점 때 토큰이 바뀌므로 이전 작업자의
 * 늦은 완료 쓰기는 DB 조건부 갱신에서 거부된다.
 */
public record RagJobClaim(Long jobId, String owner, String token) {
}
