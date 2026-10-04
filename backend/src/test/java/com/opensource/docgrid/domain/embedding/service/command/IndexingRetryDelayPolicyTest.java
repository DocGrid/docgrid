package com.opensource.docgrid.domain.embedding.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
    @DisplayName("최소 지원 지연 1ms는 Jitter 경계에서도 0ms로 줄어들지 않는다")
    void calculate_preservesMinimumSupportedDelay() {
        properties.setRetryInitialDelay(Duration.ofMillis(1));
        properties.setRetryMaxDelay(Duration.ofMillis(1));

        assertThat(properties.isRetryDelayValid()).isTrue();
        assertThat(policy.calculate(0, Duration.ZERO, 0.0))
            .isEqualTo(Duration.ofMillis(1));
        assertThat(policy.calculate(0, Duration.ZERO, Math.nextDown(1.0)))
            .isEqualTo(Duration.ofMillis(1));
    }

    @Test
    @DisplayName("Provider 하한이 Jitter 구간을 자르면 최소 분산 폭을 복원한다")
    void calculate_restoresMinimumSpreadAtProviderMinimum() {
        Duration delay = policy.calculate(0, Duration.ofSeconds(11), 0.25);

        assertThat(delay).isEqualTo(Duration.ofMillis(11_500));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providerUpperBoundaryCases")
    @DisplayName("Provider 하한이 Jitter 상한 전후에 있어도 2초 분산 폭과 하한을 지킨다")
    void calculate_keepsMinimumSpreadAcrossProviderUpperBoundary(
        String boundary,
        Duration minimumRetryDelay,
        Duration expectedLower,
        Duration expectedUpper
    ) {
        // 1. 밀리초 올림 전후의 실제 선택 구간이 최소 2초를 유지하는지 확인한다.
        assertThat(policy.calculate(0, minimumRetryDelay, 0.0)).isEqualTo(expectedLower);
        assertThat(policy.calculate(0, minimumRetryDelay, Math.nextDown(1.0)))
            .isEqualTo(expectedUpper);
        assertThat(expectedUpper.minus(expectedLower)).isEqualTo(Duration.ofSeconds(2));

        // 2. 동일한 Provider 하한을 받은 Job의 난수 위치 1,000개가 안전한 구간에 분산되는지 확인한다.
        List<Duration> delays = IntStream.range(0, 1_000)
            .mapToObj(index -> policy.calculate(0, minimumRetryDelay, index / 1_000.0))
            .toList();
        assertThat(delays).allSatisfy(delay -> {
            assertThat(delay).isGreaterThanOrEqualTo(minimumRetryDelay);
            assertThat(delay).isLessThanOrEqualTo(expectedUpper);
        });
        assertThat(delays.stream().distinct().count()).isGreaterThan(1L);
    }

    private static Stream<Arguments> providerUpperBoundaryCases() {
        return Stream.of(
            Arguments.of("상한 -50ms", Duration.ofMillis(11_950),
                Duration.ofMillis(11_950), Duration.ofMillis(13_950)),
            Arguments.of("상한 -1ns", Duration.ofSeconds(12).minusNanos(1),
                Duration.ofSeconds(12), Duration.ofSeconds(14)),
            Arguments.of("상한 동일", Duration.ofSeconds(12),
                Duration.ofSeconds(12), Duration.ofSeconds(14)),
            Arguments.of("상한 +1ns", Duration.ofSeconds(12).plusNanos(1),
                Duration.ofSeconds(12).plusNanos(1), Duration.ofSeconds(14).plusNanos(1)),
            Arguments.of("긴 Retry-After", Duration.ofMinutes(10),
                Duration.ofMinutes(10), Duration.ofMinutes(10).plusSeconds(2))
        );
    }

    @Test
    @DisplayName("Provider 하한이 설정 Backoff 상한을 자르면 그 이후 2초 폭을 확보한다")
    void calculate_restoresSpreadAfterCappedBackoffBoundary() {
        properties.setRetryMaxDelay(Duration.ofSeconds(40));
        Duration minimumRetryDelay = Duration.ofMillis(39_950);

        assertThat(policy.calculate(10, minimumRetryDelay, 0.0))
            .isEqualTo(minimumRetryDelay);
        assertThat(policy.calculate(10, minimumRetryDelay, 0.5))
            .isEqualTo(Duration.ofMillis(40_950));
        assertThat(policy.calculate(10, minimumRetryDelay, Math.nextDown(1.0)))
            .isEqualTo(Duration.ofMillis(41_950));
    }

    @Test
    @DisplayName("Jitter 비활성화 시 상한 전후의 Provider 최소 지연을 그대로 보존한다")
    void calculate_preservesProviderMinimumWithoutJitter() {
        properties.setRetryJitterRatio(0.0);

        assertThat(policy.calculate(0, Duration.ofMillis(11_950)))
            .isEqualTo(Duration.ofMillis(11_950));
        assertThat(policy.calculate(0, Duration.ofSeconds(12).minusNanos(1)))
            .isEqualTo(Duration.ofSeconds(12).minusNanos(1));
        assertThat(policy.calculate(0, Duration.ofSeconds(12)))
            .isEqualTo(Duration.ofSeconds(12));
        assertThat(policy.calculate(0, Duration.ofSeconds(12).plusNanos(1)))
            .isEqualTo(Duration.ofSeconds(12).plusNanos(1));
        assertThat(policy.calculate(0, Duration.ofMinutes(10)))
            .isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("밀리초 사이의 Provider 최소 지연을 다음 밀리초로 올려 허용 시각을 위반하지 않는다")
    void calculate_roundsProviderMinimumUpToMillis() {
        Duration minimumRetryDelay = Duration.ofSeconds(11).plusNanos(1L);

        Duration delay = policy.calculate(0, minimumRetryDelay, 0.0);

        assertThat(delay).isEqualTo(Duration.ofMillis(11_001));
        assertThat(delay).isGreaterThanOrEqualTo(minimumRetryDelay);
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
