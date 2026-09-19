package com.opensource.docgrid.domain.document.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 저장소 고아 Object 탐지가 활성화된 실행 인스턴스에서 주기 실행을 켠다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "storage.orphan-gc", name = "enabled", havingValue = "true")
public class StorageOrphanGcSchedulingConfig {
}
