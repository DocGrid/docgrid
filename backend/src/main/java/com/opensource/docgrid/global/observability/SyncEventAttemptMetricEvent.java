package com.opensource.docgrid.global.observability;

import java.util.Objects;

/**
 * 커밋이 확정된 Sync Outbox 처리 또는 Lease 회수 결과를 고정된 label로 전달한다.
 */
public record SyncEventAttemptMetricEvent(Outcome outcome) {

    /**
     * 자유 형식 오류 코드가 metric label로 유입되지 않도록 enum 값만 허용한다.
     */
    public SyncEventAttemptMetricEvent {
        Objects.requireNonNull(outcome, "outcome은 필수입니다.");
    }

    /**
     * Outbox가 실제로 확정한 처리 결과다.
     */
    public enum Outcome {
        PROCESSED("processed"),
        RETRY_SCHEDULED("retry_scheduled"),
        TERMINAL_FAILURE("terminal_failure"),
        LEASE_RECOVERED("lease_recovered");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
