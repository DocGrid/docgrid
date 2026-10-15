package com.opensource.docgrid.global.support;

import java.sql.SQLException;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 통합 테스트 준비 단계의 {@code TRUNCATE}를 PostgreSQL deadlock에 한해 재시도한다.
 *
 * <p>Spring Context의 백그라운드 스케줄러(대시보드 집계 등)가 한 트랜잭션에서 여러 테이블을 읽는 동안
 * {@code TRUNCATE}가 테이블 잠금을 다른 순서로 잡으면 PostgreSQL이 한쪽을 deadlock으로 중단시킨다.
 * {@code TRUNCATE}는 다시 실행해도 결과가 같으므로 deadlock으로 중단된 경우에만 짧게 기다렸다가 다시 실행한다.
 * deadlock이 아닌 오류와 재시도 한도를 넘긴 deadlock은 그대로 던진다. 트랜잭션 안에서 호출하면 중단된
 * 트랜잭션을 되살릴 수 없으므로 자동 커밋으로 실행하는 준비 단계에서만 사용한다.
 */
public final class TruncateDeadlockRetry {

    private static final String DEADLOCK_DETECTED_SQL_STATE = "40P01";
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MILLIS = 200L;

    private TruncateDeadlockRetry() {
    }

    public static void execute(JdbcTemplate jdbcTemplate, String truncateSql) {
        for (int attempt = 1; ; attempt++) {
            try {
                jdbcTemplate.execute(truncateSql);
                return;
            } catch (PessimisticLockingFailureException exception) {
                if (attempt >= MAX_ATTEMPTS || !isDeadlock(exception)) {
                    throw exception;
                }
                sleepBeforeRetry(exception);
            }
        }
    }

    private static boolean isDeadlock(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                && DEADLOCK_DETECTED_SQL_STATE.equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static void sleepBeforeRetry(RuntimeException exception) {
        try {
            Thread.sleep(RETRY_DELAY_MILLIS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            exception.addSuppressed(interruptedException);
            throw exception;
        }
    }
}
