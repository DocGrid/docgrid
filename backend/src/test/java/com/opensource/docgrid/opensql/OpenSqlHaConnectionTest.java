package com.opensource.docgrid.opensql;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 외부 OpenSQL 3노드에서 DDL 계정과 런타임 계정의 접속 경로·권한 경계를 확인한다.
 *
 * <p>마이그레이션은 리더에 직접 접속하고 애플리케이션은 두 OpenProxy를 통해 트랜잭션 단위로 리더에 쓴다.
 * 이 테스트는 자격 증명을 출력하거나 스키마를 변경하지 않는다.
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
    @DisplayName("두 OpenProxy 진입점은 최소 권한 DocGrid 계정의 쓰기를 리더로 보낸다")
    void bothProxiesConnectToDocGrid() throws SQLException {
        // 1. 정상 시에는 두 프록시를 각각 확인하고, A 장애 시에는 살아 있는 B를 직접 확인한다.
        String[] urls = Boolean.parseBoolean(System.getenv("OPENSQL_PROXY_A_DOWN"))
            ? new String[] {"OPENSQL_PROXY_B_JDBC_URL"}
            : new String[] {"OPENSQL_PROXY_A_JDBC_URL", "OPENSQL_PROXY_B_JDBC_URL"};
        for (String urlName : urls) {
            try (Connection connection = connect(urlName, "OPENSQL_APP_USER", "OPENSQL_APP_PASSWORD")) {
                assertTransactionalAppWrite(connection);
            }
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("OpenProxy를 통과한 Hikari 트랜잭션은 리더에서 쓰기를 처리한다")
    void hikariPoolThroughProxyWritesToLeader() throws SQLException {
        // 1. 애플리케이션과 같은 Hikari 초기화 순서로 프록시 A에 새 연결을 만든다.
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("OPENSQL_PROXY_A_JDBC_URL"));
        config.setUsername(System.getenv("OPENSQL_APP_USER"));
        config.setPassword(System.getenv("OPENSQL_APP_PASSWORD"));
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(5000);
        config.setValidationTimeout(3000);

        // 2. Hikari 초기 쿼리 뒤 시작한 명시적 트랜잭션은 리더에서 처리돼야 한다.
        try (HikariDataSource dataSource = new HikariDataSource(config);
             Connection connection = dataSource.getConnection()) {
            assertTransactionalAppWrite(connection);
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("앱의 2프록시 URL은 쓰기 가능한 리더의 DocGrid에 연결된다")
    void multiHostUrlConnects() throws SQLException {
        // 1. 앱과 동일한 2프록시 URL로 명시적 쓰기 트랜잭션을 실행한다.
        try (Connection connection = connect(
                "OPENSQL_APP_JDBC_URL", "OPENSQL_APP_USER", "OPENSQL_APP_PASSWORD")) {
            assertTransactionalAppWrite(connection);
        }
    }

    private void assertTransactionalAppWrite(Connection connection) throws SQLException {
        // 1. 트랜잭션 모드 OpenProxy는 명시적 트랜잭션을 현재 primary에 고정해야 한다.
        connection.setAutoCommit(false);
        try {
            assertAppTarget(connection);
            // 2. 대상 행이 없는 UPDATE로 쓰기 경로를 확인하고 DB 변경은 남기지 않는다.
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE documents SET id = id WHERE id = ?")) {
                statement.setLong(1, -1L);
                assertThat(statement.executeUpdate()).isZero();
            }
        } finally {
            connection.rollback();
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
