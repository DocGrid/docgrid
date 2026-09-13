package com.opensource.docgrid.global.observability;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 상태 전이 이벤트가 실제 Transaction 커밋 뒤에만 Counter에 반영되는지 검증한다.
 */
@SpringJUnitConfig(AsyncPipelineMetricsAfterCommitIntegrationTest.Config.class)
@Tag("integration")
@DisplayName("비동기 파이프라인 Counter AFTER_COMMIT 통합 테스트")
class AsyncPipelineMetricsAfterCommitIntegrationTest {

    @Autowired private ApplicationEventPublisher applicationEventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SimpleMeterRegistry meterRegistry;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        meterRegistry.clear();
    }

    @Test
    @DisplayName("이벤트를 발행한 Transaction이 커밋되면 Counter가 한 번 증가한다")
    void incrementsCounterAfterCommit() {
        transactionTemplate.executeWithoutResult(status ->
            applicationEventPublisher.publishEvent(EmbeddingJobAttemptMetricEvent.success())
        );

        assertThat(successCounter()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("이벤트를 발행한 Transaction이 롤백되면 Counter가 증가하지 않는다")
    void doesNotIncrementCounterAfterRollback() {
        transactionTemplate.executeWithoutResult(status -> {
            applicationEventPublisher.publishEvent(EmbeddingJobAttemptMetricEvent.success());
            status.setRollbackOnly();
        });

        assertThat(meterRegistry.find("docgrid.embedding.job.attempts").counter()).isNull();
    }

    private double successCounter() {
        return meterRegistry.get("docgrid.embedding.job.attempts")
            .tag("outcome", "success")
            .tag("failure_type", "NONE")
            .tag("retryable", "false")
            .counter()
            .count();
    }

    /**
     * 운영 Metrics 구독자와 실제 Spring Transaction 이벤트 처리만 격리해 구성한다.
     */
    @Configuration
    @EnableTransactionManagement
    @Import(AsyncPipelineMetrics.class)
    static class Config {

        @Bean(destroyMethod = "shutdown")
        EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
