package com.opensource.docgrid.domain.document.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 파일 저장소 Adapter 설정이 현재 구현 범위의 값만 허용하는지 검증한다.
 */
@DisplayName("FileStorageProperties 테스트")
class FileStoragePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("Local Adapter는 Bucket 설정이 없으면 docgrid Namespace를 사용한다")
    void storageBucket_defaultsOnlyForLocalAdapter() {
        contextRunner.withPropertyValues("storage.type=local")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(FileStorageProperties.class).getBucket())
                    .isEqualTo("docgrid");
            });
    }

    @Test
    @DisplayName("local과 minio 설정은 해당 Adapter 종류로 바인딩된다")
    void storageType_bindsSupportedAdapters() {
        contextRunner.withPropertyValues("storage.type=minio")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(FileStorageProperties.class).getType())
                    .isEqualTo(FileStorageType.MINIO);
            });
    }

    @Test
    @DisplayName("S3 Adapter가 구현되면 s3 설정이 바인딩된다")
    void storageType_bindsS3Adapter() {
        contextRunner.withPropertyValues("storage.type=s3")
            .run(context -> {
                assertThat(context).hasNotFailed();
                FileStorageProperties properties = context.getBean(FileStorageProperties.class);
                assertThat(properties.getType()).isEqualTo(FileStorageType.S3);
                assertThat(properties.getBucket()).isNull();
            });
    }

    @Test
    @DisplayName("GCS Adapter가 구현되면 gcs 설정이 바인딩된다")
    void storageType_bindsGcsAdapter() {
        contextRunner.withPropertyValues("storage.type=gcs")
            .run(context -> {
                assertThat(context).hasNotFailed();
                FileStorageProperties properties = context.getBean(FileStorageProperties.class);
                assertThat(properties.getType()).isEqualTo(FileStorageType.GCS);
                assertThat(properties.getBucket()).isNull();
            });
    }

    /**
     * 테스트 대상 ConfigurationProperties만 등록해 설정 바인딩 경계를 격리한다.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FileStorageProperties.class)
    static class TestConfig {
    }
}
