package com.opensource.docgrid.domain.dashboard.service.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import jakarta.persistence.EntityManagerFactory;

/**
 * 대시보드 집계 1회({@code DashboardQueryService.getSummary()})가 실행하는 SQL 개수를 고정한다.
 *
 * <p>이벤트 기반 push의 debounce 설계는 "집계 1회 = 쿼리 9개(문서 3 + 작업 4 + Worker 1 + 검색 1)"를
 * 전제로 한다. 지표나 조회 방식이 바뀌어 쿼리 수가 조용히 늘어나는 것을 잡기 위해 실제
 * PostgreSQL에서 Hibernate {@link Statistics}로 prepared statement 수를 검증한다.
 *
 * <p>지표 카테고리나 쿼리를 추가·변경하면 기대값도 함께 갱신해야 한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("대시보드 집계 SQL 개수 통합 테스트")
class DashboardQueryCountIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_dashboard_query_count_test";
    private static final long EXPECTED_STATEMENT_COUNT = 9L;

    @Autowired
    private DashboardQueryService dashboardQueryService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-dashboard-query-count-test-secret-key-2026");
    }

    @AfterAll
    void dropSchema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("정상 케이스: getSummary() 1회는 SQL 9개를 실행한다")
    void getSummary_executesNineStatements() {
        // Given — 첫 호출의 초기화 비용이 섞이지 않도록 워밍업 후 통계를 비운다.
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        dashboardQueryService.getSummary();
        statistics.clear();

        // When
        dashboardQueryService.getSummary();

        // Then
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(EXPECTED_STATEMENT_COUNT);
    }
}
