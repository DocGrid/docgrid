package com.opensource.docgrid.domain.worker.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 인덱싱 Worker의 실행 여부, Polling, 동시 실행, Heartbeat, Job Lease와 Retry를 바인딩하는 설정 클래스.
 *
 * <p>{@code indexing.worker} 환경 설정을 타입 안전한 {@link Duration}으로 제공하고, 애플리케이션 시작
 * 단계에서 서로 모순되거나 0 이하인 시간 설정을 차단한다.
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "indexing.worker")
public class IndexingWorkerProperties {

    // API 전용 실행에서는 Worker 등록과 Scheduler가 동작하지 않도록 기본값을 false로 유지한다.
    private boolean enabled = false;

    @NotBlank
    private String name = "indexing-worker";

    @NotNull
    private Duration heartbeatInterval = Duration.ofSeconds(10);

    @NotNull
    private Duration deadThreshold = Duration.ofSeconds(30);

    // 등록된 Worker가 실행 슬롯을 확인하고 새 Job을 찾는 주기다.
    @NotNull
    private Duration pollingInterval = Duration.ofSeconds(1);

    // 빈 Queue가 이어질 때 DB Claim 조회를 늦출 수 있는 최대 주기다.
    @NotNull
    private Duration idleMaxPollingInterval = Duration.ofSeconds(10);

    @Min(1)
    private int maxConcurrency = 2;

    // Claim 후 Worker가 소유권을 유지하는 기본 시간이다. 만료 복구는 후속 처리에서 사용한다.
    @NotNull
    private Duration leaseDuration = Duration.ofMinutes(5);

    // 활성 실행은 Lease 만료 전에 이 주기로 소유권을 갱신한다.
    @NotNull
    private Duration leaseRenewalInterval = Duration.ofMinutes(1);

    // 만료 Lease 복구 작업의 실행 주기와 한 번에 조회할 최대 Job 수다.
    @NotNull
    private Duration leaseRecoveryInterval = Duration.ofSeconds(30);

    @Min(1)
    private int leaseRecoveryBatchSize = 100;

    // 실패한 Job의 첫 Retry 지연과 지수 Backoff 상한이다.
    @NotNull
    private Duration retryInitialDelay = Duration.ofSeconds(10);

    @NotNull
    private Duration retryMaxDelay = Duration.ofMinutes(5);

    // 지수 Backoff는 양방향, Provider 최소 지연은 이후 방향으로 분산할 때 사용할 비율이다.
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double retryJitterRatio = 0.2;

    // 종료 시 신규 Claim을 막은 뒤 활성 실행이 스스로 끝나기를 기다리는 최대 시간이다.
    @NotNull
    private Duration shutdownGracePeriod = Duration.ofSeconds(30);

    /**
     * Heartbeat가 양수이고 DEAD 기준보다 짧은지 검증한다.
     */
    @AssertTrue(message = "Heartbeat 주기는 0보다 크고 DEAD 기준 시간보다 짧아야 합니다.")
    public boolean isTimingValid() {
        return heartbeatInterval != null
            && deadThreshold != null
            && !heartbeatInterval.isZero()
            && !heartbeatInterval.isNegative()
            && deadThreshold.compareTo(heartbeatInterval) > 0;
    }

    /**
     * 빈 작업 조회가 Busy Loop가 되지 않도록 기본·최대 Polling 주기의 범위를 검증한다.
     */
    @AssertTrue(message = "Job Polling 주기는 양수이고 빈 Queue 최대 주기보다 길 수 없습니다.")
    public boolean isPollingIntervalValid() {
        return pollingInterval != null
            && idleMaxPollingInterval != null
            && !pollingInterval.isZero()
            && !pollingInterval.isNegative()
            && idleMaxPollingInterval.compareTo(pollingInterval) >= 0;
    }

    /**
     * 발급 즉시 만료되는 Lease가 만들어지지 않도록 Lease 기간이 양수인지 검증한다.
     */
    @AssertTrue(message = "Lease 기간은 0보다 커야 합니다.")
    public boolean isLeaseDurationValid() {
        return leaseDuration != null
            && !leaseDuration.isZero()
            && !leaseDuration.isNegative();
    }

    /**
     * 활성 Job이 만료 전에 갱신될 수 있도록 갱신 주기가 양수이고 Lease 기간보다 짧은지 검증한다.
     */
    @AssertTrue(message = "Lease 갱신 주기는 0보다 크고 Lease 기간보다 짧아야 합니다.")
    public boolean isLeaseRenewalIntervalValid() {
        return leaseRenewalInterval != null
            && leaseDuration != null
            && !leaseRenewalInterval.isZero()
            && !leaseRenewalInterval.isNegative()
            && leaseRenewalInterval.compareTo(leaseDuration) < 0;
    }

    /**
     * 만료 Lease 복구 Scheduler가 과도하게 반복되지 않도록 실행 주기가 양수인지 검증한다.
     */
    @AssertTrue(message = "Lease 복구 주기는 0보다 커야 합니다.")
    public boolean isLeaseRecoveryIntervalValid() {
        return leaseRecoveryInterval != null
            && !leaseRecoveryInterval.isZero()
            && !leaseRecoveryInterval.isNegative();
    }

    /**
     * Retry 지연이 모두 양수이고 최대 지연이 초기 지연보다 짧지 않은지 검증한다.
     */
    @AssertTrue(message = "Retry 최대 지연은 양수인 초기 지연보다 짧을 수 없습니다.")
    public boolean isRetryDelayValid() {
        return retryInitialDelay != null
            && retryMaxDelay != null
            && !retryInitialDelay.isZero()
            && !retryInitialDelay.isNegative()
            && retryMaxDelay.compareTo(retryInitialDelay) >= 0;
    }

    /**
     * 즉시 종료는 허용하되 음수 대기 시간은 Executor 종료 계약으로 사용할 수 없으므로 차단한다.
     */
    @AssertTrue(message = "Worker 종료 유예 시간은 0보다 작을 수 없습니다.")
    public boolean isShutdownGracePeriodValid() {
        return shutdownGracePeriod != null
            && !shutdownGracePeriod.isNegative();
    }
}
