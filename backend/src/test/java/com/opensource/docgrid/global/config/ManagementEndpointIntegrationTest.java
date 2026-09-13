package com.opensource.docgrid.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 별도 Management 서버의 공개 범위, health group, Prometheus 출력을 실제 HTTP 경계에서 검증한다.
 *
 * <p>H2는 DB health와 HikariCP 계측을 재현하는 테스트 전용 인프라다. Redis는 연결할 수 없는 포트를
 * 사용해 fail-open 의존성이 readiness를 내리지 않는지 확인한다.
 */
@Tag("integration")
@DisplayName("Management 엔드포인트 통합 테스트")
@AutoConfigureObservability
@SpringBootTest(
    classes = ManagementEndpointIntegrationTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "management.server.port=0",
        "spring.datasource.url=jdbc:h2:mem:management;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",
        "spring.data.redis.timeout=100ms"
    }
)
@Import(ManagementEndpointSecurityConfig.class)
class ManagementEndpointIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int applicationPort;

    @LocalManagementPort
    private int managementPort;

    @Test
    @DisplayName("health probe와 Prometheus는 Management 포트에서 인증 없이 조회한다")
    void exposedEndpoints_arePublicOnManagementPort() {
        assertThat(getManagement("/actuator/health/liveness").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getManagement("/actuator/health/readiness").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getManagement("/actuator/prometheus").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("민감한 Actuator 엔드포인트와 애플리케이션 포트의 Actuator 경로는 노출하지 않는다")
    void unexposedEndpoints_areNotAvailable() {
        assertThat(getManagement("/actuator/env").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getManagement("/actuator/configprops").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> applicationResponse = restTemplate.getForEntity(
            "http://127.0.0.1:" + applicationPort + "/actuator/prometheus",
            String.class
        );
        assertThat(applicationResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Redis를 사용할 수 없어도 DB가 정상이면 readiness는 UP이다")
    void readiness_excludesFailOpenRedis() {
        ResponseEntity<String> overallHealth = getManagement("/actuator/health");
        ResponseEntity<String> readiness = getManagement("/actuator/health/readiness");

        assertThat(overallHealth.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    @DisplayName("Prometheus 출력에 JVM, HTTP 서버, HikariCP 메트릭이 포함된다")
    void prometheus_exportsRuntimeAndDatabaseMetrics() {
        // HTTP 계측 시계열이 생성된 뒤 scrape해 자동 계측 세 종류를 한 응답에서 확인한다.
        restTemplate.getForEntity("http://127.0.0.1:" + applicationPort + "/not-found", String.class);
        String metrics = getManagement("/actuator/prometheus").getBody();

        assertThat(metrics)
            .contains("jvm_memory_used_bytes")
            .contains("http_server_requests_seconds_count")
            .contains("hikaricp_connections");
    }

    private ResponseEntity<String> getManagement(String path) {
        return restTemplate.getForEntity("http://127.0.0.1:" + managementPort + path, String.class);
    }

    /**
     * DocGrid 전체 외부 인프라와 분리해 Actuator 자동 설정과 Management 보안만 기동한다.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
