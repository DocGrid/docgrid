package com.opensource.docgrid.opensql;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 외부 OpenSQL 3노드에서 DDL 계정과 런타임 계정의 접속 경로·권한 경계를 확인한다.
 *
 * <p>애플리케이션과 마이그레이션은 각자 다른 계정으로 현재 리더를 사용한다. OpenProxy 접속은
 * 세션 라우팅의 별도 진단 대상이다. 이 테스트는 자격 증명을 출력하거나 스키마를 변경하지 않는다.
 */
@Tag("integration")
@Tag("opensql-ha-connection")
@DisplayName("OpenSQL 3노드 애플리케이션·마이그레이션 접속 경계")
class OpenSqlHaConnectionTest {

    @Test
    @Timeout(30)
    @DisplayName("별도 마이그레이션 계정은 리더의 DocGrid DB에 접속한다")
    void migrationAccountConnectsToLeader() throws SQLException {
        // 1. 프록시를 우회한 경로가 현재 쓰기 가능한 리더인지 확인한다.
        try (Connection connection = connect(
                "OPENSQL_MIGRATION_JDBC_URL", "OPENSQL_MIGRATION_USER", "OPENSQL_MIGRATION_PASSWORD")) {
            assertThat(connection.getMetaData().getUserName()).isEqualTo("docgrid_migrator");
            assertClusterTarget(connection);
            assertThat(queryBoolean(connection, "SELECT has_schema_privilege(current_user, 'public', 'CREATE')"))
                .isTrue();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("사용 가능한 OpenProxy 진입점은 최소 권한 DocGrid 계정으로 연결된다")
    void bothProxiesConnectToDocGrid() throws SQLException {
        // 1. 정상 시에는 두 프록시를 각각 확인하고, A 장애 시에는 살아 있는 B를 직접 확인한다.
        String[] urls = Boolean.parseBoolean(System.getenv("OPENSQL_PROXY_A_DOWN"))
            ? new String[] {"OPENSQL_PROXY_B_JDBC_URL"}
            : new String[] {"OPENSQL_PROXY_A_JDBC_URL", "OPENSQL_PROXY_B_JDBC_URL"};
        for (String urlName : urls) {
            try (Connection connection = connect(urlName, "OPENSQL_APP_USER", "OPENSQL_APP_PASSWORD")) {
                selectPrimary(connection);
                assertAppTarget(connection);
            }
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("앱의 3호스트 URL은 쓰기 가능한 리더의 DocGrid에 연결된다")
    void multiHostUrlConnects() throws SQLException {
        // 1. PostgreSQL 3호스트 URL이 현재 리더를 선택하는지 확인한다.
        try (Connection connection = connect(
                "OPENSQL_APP_JDBC_URL", "OPENSQL_APP_USER", "OPENSQL_APP_PASSWORD")) {
            assertAppTarget(connection);
        }
    }

    private void selectPrimary(Connection connection) throws SQLException {
        // 진단용 JDBC 연결은 초기 쿼리 전에 역할을 지정해 복제본에 세션이 고정되지 않게 한다.
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SERVER ROLE TO 'primary'");
        }
    }

    private Connection connect(String urlName, String userName, String passwordName) throws SQLException {
        return DriverManager.getConnection(
            System.getenv(urlName), System.getenv(userName), System.getenv(passwordName));
    }

    private void assertAppTarget(Connection connection) throws SQLException {
        assertThat(connection.getMetaData().getUserName()).isEqualTo("docgrid_app");
        assertClusterTarget(connection);
        // 런타임 계정은 Flyway가 소유한 스키마를 변경할 수 없어야 한다.
        assertThat(queryBoolean(connection, "SELECT has_schema_privilege(current_user, 'public', 'CREATE')"))
            .isFalse();
    }

    private void assertClusterTarget(Connection connection) throws SQLException {
        assertThat(connection.getCatalog()).isEqualTo("docgrid");
        assertThat(queryBoolean(connection, "SELECT pg_is_in_recovery()"))
            .isFalse();
    }

    private boolean queryBoolean(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getBoolean(1);
        }
    }
}
