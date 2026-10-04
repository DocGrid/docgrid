package com.opensource.docgrid.domain.embedding.service.command;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 인덱싱 Job의 지수 Backoff, Jitter와 Provider 최소 지연을 하나의 재예약 지연으로 계산한다.
 *
 * <p>Job 상태 변경이나 시각 저장은 수행하지 않는다. 설정 최대 지연은 애플리케이션 Backoff에만
 * 적용하며 외부 `Retry-After`는 최소 재호출 시각 이후의 제한된 구간으로 분산한다.
 */
@Component
@RequiredArgsConstructor
public class IndexingRetryDelayPolicy {

    private final IndexingWorkerProperties workerProperties;

    /**
     * 현재 Retry 횟수에 해당하는 다음 실행 지연을 반환한다.
     *
     * @param currentRetryCount 이미 수행한 재시도 횟수
     * @param minimumRetryDelay Provider가 요구한 최소 대기 시간, 요구가 없으면 {@link Duration#ZERO}
     * @return Jitter와 Provider 최소 대기 시간을 모두 만족하는 지연
     */
    public Duration calculate(int currentRetryCount, Duration minimumRetryDelay) {
        return calculate(
            currentRetryCount,
            minimumRetryDelay,
            ThreadLocalRandom.current().nextDouble()
        );
    }

    /**
     * 난수 경계에 따른 Retry 지연을 결정적으로 검증할 수 있도록 Jitter 단위값을 직접 받는다.
     */
    Duration calculate(
        int currentRetryCount,
        Duration minimumRetryDelay,
        double jitterUnit
    ) {
        // 1. 잘못된 Retry 상태가 예약 시각 계산으로 전파되지 않도록 입력을 검증한다.
        if (currentRetryCount < 0
            || minimumRetryDelay == null
            || minimumRetryDelay.isNegative()) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }
        if (Double.isNaN(jitterUnit) || jitterUnit < 0.0 || jitterUnit >= 1.0) {
            throw new IllegalArgumentException("jitterUnit은 0 이상 1 미만이어야 합니다.");
        }

        // 2. 지수 지연을 설정 상한 안에서 계산한 뒤 유효 Jitter 구간 선택에 사용한다.
        Duration maxDelay = workerProperties.getRetryMaxDelay();
        Duration exponentialDelay = calculateExponentialDelay(currentRetryCount, maxDelay);
        // 3. 상한과 Provider 최소 지연을 함께 반영해 경계 시각 집중을 피한다.
        return applyJitter(exponentialDelay, maxDelay, minimumRetryDelay, jitterUnit);
    }

    /**
     * 최초 지연을 Retry 횟수만큼 두 배로 늘리되 설정된 최대 지연을 넘기지 않는다.
     */
    private Duration calculateExponentialDelay(int currentRetryCount, Duration maxDelay) {
        // 1. 첫 실패의 재시도에는 설정된 최초 지연을 그대로 적용한다.
        Duration delay = workerProperties.getRetryInitialDelay();
        for (int retry = 0; retry < currentRetryCount; retry++) {
            // 2. 두 배가 상한에 닿는 순간 반환해 잘못된 큰 Retry 횟수에서도 Duration Overflow를 피한다.
            if (delay.compareTo(maxDelay.minus(delay)) >= 0) {
                return maxDelay;
            }

            // 3. 아직 상한 미만인 구간만 두 배로 늘린다.
            delay = delay.multipliedBy(2);
        }
        return delay;
    }

    /**
     * 동일 Retry 단계의 Job이 상한이나 Provider 최소 시각에 몰리지 않도록 유효 구간을 선택한다.
     */
    private Duration applyJitter(
        Duration delay,
        Duration maxDelay,
        Duration minimumRetryDelay,
        double jitterUnit
    ) {
        // 1. Jitter가 비활성화된 환경에서는 입력 지연을 그대로 보존한다.
        double jitterRatio = workerProperties.getRetryJitterRatio();
        if (jitterRatio == 0.0) {
            return delay.compareTo(minimumRetryDelay) >= 0 ? delay : minimumRetryDelay;
        }

        // 2. 최대 지연으로 자르기 전에 유효한 Jitter 하한과 상한을 먼저 고정한다.
        long delayMillis = delay.toMillis();
        long lowerMillis = Math.max(
            1L,
            Math.round(delayMillis * (1.0 - jitterRatio))
        );
        long upperMillis = Math.min(
            maxDelay.toMillis(),
            Math.max(lowerMillis, Math.round(delayMillis * (1.0 + jitterRatio)))
        );
        Duration lowerDelay = Duration.ofMillis(lowerMillis);
        Duration upperDelay = Duration.ofMillis(upperMillis);

        long spreadMillis = positiveJitterSpreadMillis(minimumRetryDelay, jitterRatio);

        // 3. Provider 하한이 구간을 자르면 밀리초 올림 후에도 최소 분산 폭을 유지한다.
        if (minimumRetryDelay.compareTo(upperDelay) < 0) {
            Duration effectiveLower = minimumRetryDelay.compareTo(lowerDelay) > 0
                ? minimumRetryDelay
                : lowerDelay;
            long effectiveUpperMillis = upperMillis;
            if (minimumRetryDelay.compareTo(lowerDelay) > 0) {
                effectiveUpperMillis = Math.max(
                    upperMillis,
                    Math.addExact(ceilToMillis(effectiveLower), spreadMillis)
                );
            }
            return randomBetween(
                effectiveLower,
                Duration.ofMillis(effectiveUpperMillis),
                jitterUnit
            );
        }

        // 4. 최소 지연이 구간 끝에 닿거나 넘으면 그 이후 방향으로만 제한된 Jitter를 적용한다.
        long offsetMillis = Math.round(spreadMillis * jitterUnit);
        return minimumRetryDelay.plusMillis(offsetMillis);
    }

    /**
     * Provider 하한 뒤의 분산 폭을 최초 Backoff의 Jitter 폭 이하로 제한한다.
     */
    private long positiveJitterSpreadMillis(Duration minimumRetryDelay, double jitterRatio) {
        Duration spreadBase = minimumRetryDelay.compareTo(workerProperties.getRetryInitialDelay()) < 0
            ? minimumRetryDelay
            : workerProperties.getRetryInitialDelay();
        return Math.max(1L, Math.round(spreadBase.toMillis() * jitterRatio));
    }

    /**
     * 두 Duration 경계를 포함하는 밀리초 구간에서 Jitter 단위값에 해당하는 지연을 선택한다.
     */
    private Duration randomBetween(Duration lowerDelay, Duration upperDelay, double jitterUnit) {
        // Provider 최소 지연의 나노초를 내림하면 허용 시각보다 일찍 실행될 수 있어 하한만 올림한다.
        long lowerMillis = ceilToMillis(lowerDelay);
        long rangeMillis = upperDelay.toMillis() - lowerMillis;
        return Duration.ofMillis(lowerMillis + Math.round(rangeMillis * jitterUnit));
    }

    /**
     * Duration을 밀리초로 줄이면서 원래 시각보다 이른 값이 되지 않도록 올림한다.
     */
    private long ceilToMillis(Duration duration) {
        long millis = duration.toMillis();
        return duration.equals(Duration.ofMillis(millis)) ? millis : millis + 1L;
    }
}
