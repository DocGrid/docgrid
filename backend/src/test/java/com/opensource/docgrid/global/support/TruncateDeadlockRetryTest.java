package com.opensource.docgrid.global.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("TruncateDeadlockRetry 단위 테스트")
class TruncateDeadlockRetryTest {

    private static final String TRUNCATE_SQL = "TRUNCATE TABLE documents RESTART IDENTITY CASCADE";

    @Mock private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("deadlock으로 한 번 중단되면 다시 실행해 성공한다")
    void execute_retries_whenDeadlockDetected() {
        willThrow(lockingFailure("40P01"))
            .willDoNothing()
            .given(jdbcTemplate).execute(TRUNCATE_SQL);

        assertThatCode(() -> TruncateDeadlockRetry.execute(jdbcTemplate, TRUNCATE_SQL))
            .doesNotThrowAnyException();

        then(jdbcTemplate).should(times(2)).execute(TRUNCATE_SQL);
    }

    @Test
    @DisplayName("deadlock이 아닌 잠금 오류는 재시도하지 않고 그대로 던진다")
    void execute_throws_whenLockingFailureIsNotDeadlock() {
        PessimisticLockingFailureException lockTimeout = lockingFailure("55P03");
        willThrow(lockTimeout).given(jdbcTemplate).execute(TRUNCATE_SQL);

        assertThatThrownBy(() -> TruncateDeadlockRetry.execute(jdbcTemplate, TRUNCATE_SQL))
            .isSameAs(lockTimeout);

        then(jdbcTemplate).should(times(1)).execute(TRUNCATE_SQL);
    }

    @Test
    @DisplayName("deadlock이 재시도 한도까지 반복되면 마지막 예외를 던진다")
    void execute_throws_whenDeadlockPersists() {
        PessimisticLockingFailureException deadlock = lockingFailure("40P01");
        willThrow(deadlock).given(jdbcTemplate).execute(TRUNCATE_SQL);

        assertThatThrownBy(() -> TruncateDeadlockRetry.execute(jdbcTemplate, TRUNCATE_SQL))
            .isSameAs(deadlock);

        then(jdbcTemplate).should(times(3)).execute(TRUNCATE_SQL);
    }

    private static PessimisticLockingFailureException lockingFailure(String sqlState) {
        return new PessimisticLockingFailureException(
            "TRUNCATE 잠금 실패",
            new SQLException("lock failure", sqlState)
        );
    }
}
