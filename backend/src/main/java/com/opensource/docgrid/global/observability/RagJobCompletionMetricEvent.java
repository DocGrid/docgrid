package com.opensource.docgrid.global.observability;

import java.util.Objects;

/**
 * 커밋이 확정된 RAG Job의 최종 처리 경로를 고정된 결과 label로 전달한다.
 */
public record RagJobCompletionMetricEvent(Outcome outcome) {

    /**
     * 자유 형식 결과가 metric label로 유입되지 않도록 enum 값만 허용한다.
     */
    public RagJobCompletionMetricEvent {
        Objects.requireNonNull(outcome, "outcome은 필수입니다.");
    }

    /**
     * 정상 답변과 각 fallback·회수 원인을 구분하는 RAG 완료 결과다.
     */
    public enum Outcome {
        SUCCESS("success"),
        NO_CONTEXT("no_context"),
        PROVIDER_FALLBACK("provider_fallback"),
        TIMEOUT_SWEPT("timeout_swept"),
        UNEXPECTED_FAILURE("unexpected_failure");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
