package com.opensource.docgrid.domain.worker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * Worker Polling, 실행 동시성, Lease, Retry와 종료 설정의 기본값 및 시작 단계 유효성 검사를 검증한다.
 *
 * <p>정상적인 기간은 허용하고 0·음수 또는 계산 정밀도보다 짧은 기간은 차단하는지 확인한다.
 */
@DisplayName("IndexingWorkerProperties 테스트")
class IndexingWorkerPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("기본 Polling은 1초부터 빈 Queue 최대 10초까지이고 동시 실행은 2다")
    void defaultExecutionSettings_areValid() {
        IndexingWorkerProperties properties = new IndexingWorkerProperties();

        assertThat(properties.getPollingInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.getIdleMaxPollingInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getMaxConcurrency()).isEqualTo(2);
        assertThat(properties.getShutdownGracePeriod()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.isPollingIntervalValid()).isTrue();
        assertThat(properties.isShutdownGracePeriodValid()).isTrue();
    }

    @Test
    @DisplayName("Polling 주기는 양수이고 빈 Queue 최대 주기보다 길 수 없다")
    void executionIntervals_areInvalid_when_outOfRange() {
        IndexingWorkerProperties properties = new IndexingWorkerProperties();

        properties.setPollingInterval(Duration.ZERO);
        assertThat(properties.isPollingIntervalValid()).isFalse();

        properties.setPollingInterval(Duration.ofMillis(1));
        properties.setIdleMaxPollingInterval(Duration.ZERO);
        assertThat(properties.isPollingIntervalValid()).isFalse();

        properties.setIdleMaxPollingInterval(Duration.ofMillis(1));
        assertThat(properties.isPollingIntervalValid()).isTrue();

        properties.setPollingInterval(Duration.ofMillis(2));
        assertThat(properties.isPollingIntervalValid()).isFalse();

        properties.setPollingInterval(Duration.ofMillis(1));
        properties.setShutdownGracePeriod(Duration.ZERO);
        assertThat(properties.isShutdownGracePeriodValid()).isTrue();

        properties.setShutdownGracePeriod(Duration.ofNanos(-1));
        assertThat(properties.isShutdownGracePeriodValid()).isFalse();
    }

    @Test
    @DisplayName("Lease 갱신 주기는 양수이고 Lease 기간보다 짧아야 한다")
    void leaseRenewalInterval_isValid_onlyBeforeLeaseExpiry() {
        IndexingWorkerProperties properties = new IndexingWorkerProperties();
        properties.setLeaseDuration(Duration.ofMinutes(5));

        properties.setLeaseRenewalInterval(Duration.ofMinutes(1));
        assertThat(properties.isLeaseRenewalIntervalValid()).isTrue();

        properties.setLeaseRenewalInterval(Duration.ZERO);
        assertThat(properties.isLeaseRenewalIntervalValid()).isFalse();

        properties.setLeaseRenewalInterval(Duration.ofMinutes(5));
        assertThat(properties.isLeaseRenewalIntervalValid()).isFalse();
    }

    @Test
    @DisplayName("기본 Lease 기간은 5분이며 유효하다")
    void defaultLeaseDuration_isValid() {
        IndexingWorkerProperties properties = new IndexingWorkerProperties();

        assertThat(properties.getLeaseDuration()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.isLeaseDurationValid()).isTrue();
    }

    @Test
    @DisplayName("Lease 기간이 0 이하이면 유효하지 않다")
    void leaseDuration_isInvalid_when_notPositive() {
        IndexingWorkerProperties properties = new IndexingWorkerProperties();

        properties.setLeaseDuration(Duration.ZERO);
        assertThat(properties.isLeaseDurationValid()).isFalse();

        properties.setLeaseDuration(Duration.ofSeconds(-1));
        assertThat(properties.isLeaseDurationValid()).isFalse();
    }

    @Test
    @DisplayName("기본 Retry 지연은 10초부터 5분까지이며 유효하다")
    void defaultRetryDelay_isValid() {
        IndexingWorkerProperties properties = new IndexingWorkerProperties();

        assertThat(properties.getRetryInitialDelay()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getRetryMaxDelay()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.getRetryJitterRatio()).isEqualTo(0.2);
        assertThat(properties.isRetryDelayValid()).isTrue();
        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    @DisplayName("Retry Jitter 비율은 0 이상 1 이하만 허용한다")
    void retryJitterRatio_isInvalid_when_outOfRange() {
        IndexingWorkerProperties properties = new IndexingWorkerProperties();

        properties.setRetryJitterRatio(-0.01);
        assertThat(validator.validate(properties)).isNotEmpty();

        properties.setRetryJitterRatio(1.01);
        assertThat(validator.validate(properties)).isNotEmpty();

        properties.setRetryJitterRatio(0.0);
        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    @DisplayName("Retry 지연이 0 이하이거나 최대 지연이 더 짧으면 유효하지 않다")
    void retryDelay_isInvalid_when_nonPositiveOrReversed() {
        IndexingWorkerProperties properties = new IndexingWorkerProperties();

        properties.setRetryInitialDelay(Duration.ZERO);
        assertThat(properties.isRetryDelayValid()).isFalse();

        properties.setRetryInitialDelay(Duration.ofSeconds(-1));
        assertThat(properties.isRetryDelayValid()).isFalse();

        properties.setRetryInitialDelay(Duration.ofSeconds(10));
        properties.setRetryMaxDelay(Duration.ofSeconds(9));
        assertThat(properties.isRetryDelayValid()).isFalse();

        properties.setRetryMaxDelay(Duration.ofSeconds(10));
        assertThat(properties.isRetryDelayValid()).isTrue();
    }

    @Test
    @DisplayName("Retry 초기 지연은 1ms부터 허용한다")
    void retryDelay_isValid_fromOneMillisecond() {
        IndexingWorkerProperties properties = new IndexingWorkerProperties();
        properties.setRetryMaxDelay(Duration.ofMillis(1));

        // 밀리초 기반 Jitter 계산에서 0ms로 손실되는 더 짧은 설정은 시작 전에 거부한다.
        properties.setRetryInitialDelay(Duration.ofNanos(500_000));
        assertThat(properties.isRetryDelayValid()).isFalse();
        assertThat(validator.validate(properties)).isNotEmpty();

        properties.setRetryInitialDelay(Duration.ofMillis(1));
        assertThat(properties.isRetryDelayValid()).isTrue();
        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    @DisplayName("기본 Lease 복구 주기는 30초이고 Batch 크기는 100이다")
    void defaultLeaseRecoverySettings_areValid() {
        IndexingWorkerProperties properties = new IndexingWorkerProperties();

        assertThat(properties.getLeaseRecoveryInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getLeaseRecoveryBatchSize()).isEqualTo(100);
        assertThat(properties.isLeaseRecoveryIntervalValid()).isTrue();
        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    @DisplayName("Lease 복구 주기가 0 이하이거나 Batch 크기가 1보다 작으면 유효하지 않다")
    void leaseRecoverySettings_areInvalid_when_intervalOrBatchIsNotPositive() {
        IndexingWorkerProperties properties = new IndexingWorkerProperties();

        properties.setLeaseRecoveryInterval(Duration.ZERO);
        assertThat(properties.isLeaseRecoveryIntervalValid()).isFalse();

        properties.setLeaseRecoveryInterval(Duration.ofSeconds(-1));
        assertThat(properties.isLeaseRecoveryIntervalValid()).isFalse();

        properties.setLeaseRecoveryInterval(Duration.ofSeconds(1));
        properties.setLeaseRecoveryBatchSize(0);
        Set<ConstraintViolation<IndexingWorkerProperties>> violations = validator.validate(properties);
        assertThat(violations)
            .extracting(ConstraintViolation::getPropertyPath)
            .extracting(Object::toString)
            .contains("leaseRecoveryBatchSize");
    }
}
