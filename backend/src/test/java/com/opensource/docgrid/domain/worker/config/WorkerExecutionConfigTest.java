package com.opensource.docgrid.domain.worker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 시험 문서 한정 Worker가 일반 프로필에서 시작되지 않도록 하는 실행 설정 경계를 검증한다.
 */
@DisplayName("WorkerExecutionConfig 테스트")
class WorkerExecutionConfigTest {

    private final WorkerExecutionConfig config = new WorkerExecutionConfig();

    @Test
    @DisplayName("문서 버전 필터가 있는 Worker는 시험 프로필 밖에서 시작을 거부한다")
    void workerJobExecutor_rejectsScopedWorkerOutsideTestProfile() {
        IndexingWorkerProperties properties = new IndexingWorkerProperties();
        properties.setDocumentVersionIdFilter(42L);

        assertThatThrownBy(() -> config.workerJobExecutor(properties, new MockEnvironment()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("worker-scope-test");
    }

    @Test
    @DisplayName("시험 프로필에서는 문서 버전 필터가 있는 Worker를 시작한다")
    void workerJobExecutor_acceptsScopedWorkerInTestProfile() {
        IndexingWorkerProperties properties = new IndexingWorkerProperties();
        properties.setDocumentVersionIdFilter(42L);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("worker-scope-test");

        ThreadPoolExecutor executor = config.workerJobExecutor(properties, environment);
        try {
            assertThat(executor.getMaximumPoolSize()).isEqualTo(properties.getMaxConcurrency());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("필터가 없으면 기존 운영 프로필에서도 Worker를 시작한다")
    void workerJobExecutor_preservesDefaultWorkerBehavior() {
        IndexingWorkerProperties properties = new IndexingWorkerProperties();

        ThreadPoolExecutor executor = config.workerJobExecutor(properties, new MockEnvironment());
        try {
            assertThat(executor.getMaximumPoolSize()).isEqualTo(properties.getMaxConcurrency());
        } finally {
            executor.shutdownNow();
        }
    }
}
