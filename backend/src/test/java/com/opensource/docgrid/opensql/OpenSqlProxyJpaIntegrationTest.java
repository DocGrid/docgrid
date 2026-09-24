package com.opensource.docgrid.opensql;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.failover.entity.FailoverEvent;
import com.opensource.docgrid.domain.failover.enums.FailoverEventType;
import com.opensource.docgrid.domain.failover.enums.FailoverStatus;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실제 OpenProxy 경유 Hikari·JPA 트랜잭션이 리더에서 쓰기를 실행하는지 검증한다.
 *
 * <p>외부 클러스터 전용 수동 테스트이며 Flyway와 백그라운드 작업을 실행하지 않는다.
 */
@Tag("opensql-ha-connection")
@ActiveProfiles("opensql-ha")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.flyway.enabled=false",
    "management.server.port=0",
    "indexing.worker.enabled=false",
    "sync.dispatcher.enabled=false",
    "sync.reconciliation.enabled=false"
})
@Transactional
@Rollback
@DisplayName("OpenProxy를 경유한 JPA 쓰기 경로")
class OpenSqlProxyJpaIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    @Timeout(30)
    @DisplayName("명시적 JPA 트랜잭션은 리더에서 UPDATE와 엔티티 INSERT를 실행하고 롤백한다")
    void jpaTransactionWritesToLeader() {
        // 1. 조회로 확인한 현재 트랜잭션의 DB 역할이 primary여야 한다.
        boolean standby = (Boolean) entityManager.createNativeQuery("SELECT pg_is_in_recovery()")
            .getSingleResult();
        assertThat(standby).isFalse();

        // 2. JPA가 보낸 UPDATE를 실행하되 대상 행이 없어 기존 데이터는 바꾸지 않는다.
        int updatedRows = entityManager.createNativeQuery("UPDATE documents SET id = id WHERE id = -1")
            .executeUpdate();
        assertThat(updatedRows).isZero();

        // 3. Hibernate INSERT도 프록시를 통과시키고 테스트 트랜잭션 종료 시 롤백한다.
        FailoverEvent probe = FailoverEvent.builder()
            .eventType(FailoverEventType.RECONNECT_SUCCESS)
            .status(FailoverStatus.SUCCESS)
            .message("OpenProxy compatibility probe; rolled back")
            .occurredAt(LocalDateTime.now())
            .build();
        entityManager.persist(probe);
        entityManager.flush();
        assertThat(probe.getId()).isNotNull();
    }
}
