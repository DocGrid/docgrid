package com.opensource.docgrid.global.config;

import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.opensource.docgrid.domain.permission.service.SynchronousCommitTransactionManager;

/**
 * strict 동기 클러스터로 확인된 환경에서만 권한 회수 COMMIT 경고 판정을 활성화한다.
 * Patroni 설정이나 일반 업무의 synchronous_commit 값을 변경하지 않는다.
 */
@Configuration
@ConditionalOnProperty(name = "permission.revocation.synchronous-enabled", havingValue = "true")
public class PermissionDurabilityConfiguration {
    @Bean
    public SynchronousCommitTransactionManager transactionManager(EntityManagerFactory factory, DataSource dataSource) {
        return new SynchronousCommitTransactionManager(factory, dataSource);
    }
}
