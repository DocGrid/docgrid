package com.opensource.docgrid.domain.document.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 저장소 고아 Object 탐지의 실행 여부와 안전 유예 시간, 한 번의 비교 크기를 제공한다.
 * 1단계는 탐지만 수행하며 삭제 모드나 삭제 관련 설정을 노출하지 않는다.
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "storage.orphan-gc")
public class StorageOrphanGcProperties {

    private boolean enabled;

    @NotNull
    private Duration initialDelay = Duration.ofMinutes(1);

    @NotNull
    private Duration interval = Duration.ofHours(24);

    @NotNull
    private Duration gracePeriod = Duration.ofHours(72);

    @Min(1)
    @Max(1_000)
    private int pageSize = 500;

    /** Scheduler와 유예 시간이 역방향 또는 busy loop가 되지 않는지 검증한다. */
    @AssertTrue(message = "고아 Object 탐지 시간 설정이 올바르지 않습니다.")
    public boolean isTimingValid() {
        return initialDelay != null && !initialDelay.isNegative()
            && interval != null && !interval.isNegative() && !interval.isZero()
            && gracePeriod != null && !gracePeriod.isNegative() && !gracePeriod.isZero();
    }
}
