package com.opensource.docgrid.domain.embedding.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * Job Retry 지연의 지수 증가, Jitter 유효 구간, Backoff 상한과 Provider 최소 지연을 검증한다.
 *
 * <p>DB `next_retry_at` 저장과 Worker Claim은 이 순수 정책 단위 테스트의 경계에 포함하지 않는다.
 */
@DisplayName("IndexingRetryDelayPolicy 테스트")
class IndexingRetryDelayPolicyTest {

    private IndexingWorkerProperties properties;
    private IndexingRetryDelayPolicy policy;

    @BeforeEach
    void setUp() {
        properties = new IndexingWorkerProperties();
        policy = new IndexingRetryDelayPolicy(properties);
    }

    @Test
    @DisplayName("첫 Retry 10초의 ±20% 구간에서 난수 위치에 해당하는 지연을 선택한다")
    void calculate_appliesConfiguredJitterRange() {
        assertThat(policy.calculate(0, Duration.ZERO, 0.0))
            .isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.calculate(0, Duration.ZERO, 0.5))
            .isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.calculate(0, Duration.ZERO, Math.nextDown(1.0)))
            .isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    @DisplayName("Backoff 상한에서는 잘린 결과 대신 32~40초 유효 구간 전체에 분산한다")
    void calculate_distributesWithinCappedJitterRange() {
        properties.setRetryMaxDelay(Duration.ofSeconds(40));

        assertThat(policy.calculate(10, Duration.ZERO, 0.0))
            .isEqualTo(Duration.ofSeconds(32));
        assertThat(policy.calculate(10, Duration.ZERO, 0.5))
            .isEqualTo(Duration.ofSeconds(36));
        assertThat(policy.calculate(10, Duration.ZERO, 0.75))
            .isEqualTo(Duration.ofSeconds(38));
        assertThat(policy.calculate(10, Duration.ZERO, Math.nextDown(1.0)))
            .isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    @DisplayName("Backoff 상한의 균등한 난수 입력 1,000개가 최대 지연 한 점에 합쳐지지 않는다")
    void calculate_doesNotPileDeterministicSamplesAtMaximumDelay() {
        properties.setRetryMaxDelay(Duration.ofSeconds(40));

        List<Duration> delays = IntStream.range(0, 1_000)
            .mapToObj(index -> policy.calculate(10, Duration.ZERO, index / 1_000.0))
            .toList();

        assertThat(delays).doesNotContain(Duration.ofSeconds(40));
        assertThat(delays.stream().distinct().count()).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("Jitter가 0이면 Retry 횟수에 따라 10·20·40초로 지수 증가한다")
    void calculate_appliesExponentialBackoff() {
        properties.setRetryJitterRatio(0.0);

        assertThat(policy.calculate(0, Duration.ZERO)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.calculate(1, Duration.ZERO)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.calculate(2, Duration.ZERO)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    @DisplayName("Provider 최소 지연이 Jitter 구간 안에 있으면 금지 구간을 제외하고 선택한다")
    void calculate_truncatesJitterRangeAtProviderMinimum() {
        Duration delay = policy.calculate(0, Duration.ofSeconds(11), 0.25);

        assertThat(delay).isEqualTo(Duration.ofMillis(11_250));
    }

    @Test
    @DisplayName("Provider 최소 지연이 Jitter 상한보다 길면 최소 지연 이후 2초 안에 분산한다")
    void calculate_addsBoundedPositiveJitterAfterProviderMinimum() {
        Duration minimumRetryDelay = Duration.ofSeconds(15);

        assertThat(policy.calculate(0, minimumRetryDelay, 0.0))
            .isEqualTo(Duration.ofSeconds(15));
        assertThat(policy.calculate(0, minimumRetryDelay, 0.5))
            .isEqualTo(Duration.ofSeconds(16));
        assertThat(policy.calculate(0, minimumRetryDelay, Math.nextDown(1.0)))
            .isEqualTo(Duration.ofSeconds(17));
    }

    @Test
    @DisplayName("긴 Provider 최소 지연에도 추가 Jitter를 최초 Backoff 비율인 2초로 제한한다")
    void calculate_capsPositiveJitterForLongProviderMinimum() {
        Duration delay = policy.calculate(
            0,
            Duration.ofMinutes(10),
            Math.nextDown(1.0)
        );

        assertThat(delay).isEqualTo(Duration.ofMinutes(10).plusSeconds(2));
    }

    @Test
    @DisplayName("지수·Jitter는 설정 상한을 지키고 더 긴 Provider 최소 지연은 보존한다")
    void calculate_capsBackoffAndPreservesProviderMinimum() {
        properties.setRetryMaxDelay(Duration.ofSeconds(40));

        assertThat(policy.calculate(10, Duration.ZERO, 0.75))
            .isEqualTo(Duration.ofSeconds(38));
        properties.setRetryJitterRatio(0.0);
        assertThat(policy.calculate(0, Duration.ofMinutes(10)))
            .isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("음수 Retry 횟수나 최소 지연은 상태 불일치로 거부한다")
    void calculate_rejectsInvalidInputs() {
        assertThatThrownBy(() -> policy.calculate(-1, Duration.ZERO))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT
            );
        assertThatThrownBy(() -> policy.calculate(0, Duration.ofSeconds(-1)))
            .isInstanceOf(DocGridException.class);
        assertThatThrownBy(() -> policy.calculate(0, Duration.ZERO, 1.0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
