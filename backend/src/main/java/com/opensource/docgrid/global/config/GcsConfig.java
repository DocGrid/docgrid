package com.opensource.docgrid.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

/**
 * GCS Adapter가 선택된 환경에서만 Cloud Storage Client를 만든다.
 * 인증과 프로젝트 선택은 실행 환경의 Application Default Credentials에 위임한다.
 */
@Configuration
@ConditionalOnProperty(prefix = "storage", name = "type", havingValue = "gcs")
public class GcsConfig {

    @Bean
    public Storage gcsStorage() {
        return StorageOptions.getDefaultInstance().getService();
    }
}
