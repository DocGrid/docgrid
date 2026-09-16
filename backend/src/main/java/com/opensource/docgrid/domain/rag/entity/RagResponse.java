package com.opensource.docgrid.domain.rag.entity;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.global.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * RAG 최종 답변 테이블.
 *
 * <p>역할: 사용자가 실제로 보게 되는 최종 답변(LLM 응답)을 저장한다.
 * 이유: search_results가 "검색 후보"라면 rag_responses는 그 후보를 근거로 LLM이 생성한 "최종 답변"이다.
 * 이 둘을 분리해야 답변 오류 원인을 검색 단계/생성 단계로 나눠 분석할 수 있다.
 * 관계: query_id -> SearchQuery(1:1에 가까운 1:N, 하나의 query에 하나의 응답을 기본으로 함).
 * index: query_id, status, created_at.
 *
 * <p>현재 비동기 RAG Worker가 Ollama 응답을 기록하며, PROCESSING으로 먼저 생성한 뒤 성공·실패 또는
 * Timeout Sweeper가 최종 상태를 확정한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "rag_responses",
        indexes = {
                @Index(name = "idx_rag_responses_query_id", columnList = "query_id"),
                @Index(name = "idx_rag_responses_status", columnList = "status"),
                @Index(name = "idx_rag_responses_created_at", columnList = "created_at")
        }
)
public class RagResponse extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이 답변이 근거로 하는 검색 요청
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "query_id", nullable = false)
    private SearchQuery query;

    /**
     * PROCESSING 상태로 처음 저장될 때는 아직 값이 없다 — Worker의 정상 완료(completeSuccess/
     * completeFailed) 또는 RagJobTimeoutSweeper의 강제 종료(forceFailIfProcessing) 중 먼저
     * 확정되는 쪽이 채운다.
     */
    @Column(name = "answer_text", columnDefinition = "TEXT")
    private String answerText;

    // LLM 제공자 이름
    @Column(name = "llm_provider", length = 50)
    private String llmProvider;

    // LLM 모델 이름
    @Column(name = "llm_model_name", length = 100)
    private String llmModelName;

    @Column(name = "prompt_text", columnDefinition = "TEXT")
    private String promptText;

    @Column(name = "input_token_count")
    private Integer inputTokenCount;

    @Column(name = "output_token_count")
    private Integer outputTokenCount;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResultStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * 병렬 Worker가 이 job을 이미 집었는지 표시한다(#340). status만으로는 "대기 중"과 "누가 이미
     * 처리 중"을 구분할 수 없어서(둘 다 PROCESSING) 별도로 둔다. RagResponseClaimService의 짧은
     * claim 트랜잭션 안에서만 채워지며, 그 밖의 완료 확정 경로(조건부 UPDATE)는 이 컬럼을 건드리지 않는다.
     */
    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Builder
    public RagResponse(SearchQuery query, String answerText, String llmProvider, String llmModelName,
                        String promptText, Integer inputTokenCount, Integer outputTokenCount, Integer latencyMs,
                        ResultStatus status, String errorMessage) {
        this.query = query;
        this.answerText = answerText;
        this.llmProvider = llmProvider;
        this.llmModelName = llmModelName;
        this.promptText = promptText;
        this.inputTokenCount = inputTokenCount;
        this.outputTokenCount = outputTokenCount;
        this.latencyMs = latencyMs;
        this.status = status;
        this.errorMessage = errorMessage;
    }

    /**
     * 이 job을 지금 이 Worker가 처리하기 시작했다는 표시를 남긴다. {@code RagResponseClaimService}의
     * 짧은 claim 트랜잭션 안에서만 호출되어야 한다 — dirty checking으로 반영되므로, 이 엔티티가
     * detached된 뒤(다른 트랜잭션/스레드로 넘어간 뒤)에 호출하면 반영되지 않는다.
     */
    public void markClaimed(LocalDateTime claimedAt) {
        this.claimedAt = claimedAt;
    }
}
