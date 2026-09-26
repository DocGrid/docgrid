package com.opensource.docgrid.opensql;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.HashSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.postgresql.PGStatement;

/**
 * 실제 두 OpenProxy에서 트랜잭션 라우팅, pgJDBC 프로토콜, 시간대 경계를 확인한다.
 *
 * <p>외부 3노드 전용 수동 시험이다. 설정·스키마를 바꾸지 않으며 역할과 backend 개수만 출력한다.
 * SQL이 어느 물리 노드에 갔는지는 별도 프록시·DB 통계와 교차 확인한다.
 */
@Tag("opensql-ha-contract")
@DisplayName("OpenProxy 라우팅·세션 계약")
class OpenSqlOpenProxyContractTest {

    private static final String[] PROXIES = {"OPENSQL_PROXY_A_JDBC_URL", "OPENSQL_PROXY_B_JDBC_URL"};

    @Test
    @Timeout(60)
    @DisplayName("자동 커밋 조회와 명시적 읽기·쓰기 트랜잭션의 DB 역할을 구분한다")
    void routesQueriesByTransactionContract() throws SQLException {
        for (String proxy : PROXIES) {
            // 1. 자동 커밋 조회의 실제 결과는 기록한다. 읽기 분산을 미리 가정하지 않는다.
            try (Connection connection = connect(proxy, null)) {
                report(proxy, "autocommit-select", isStandby(connection));
            }

            // 2. SELECT 다음 UPDATE를 같은 명시적 트랜잭션에서 실행해 primary 고정을 확인한다.
            try (Connection connection = connect(proxy, null)) {
                connection.setAutoCommit(false);
                try {
                    boolean standby = isStandby(connection);
                    report(proxy, "read-write-transaction", standby);
                    assertThat(standby).isFalse();
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE documents SET id = id WHERE id = ?")) {
                        update.setLong(1, -1L);
                        assertThat(update.executeUpdate()).isZero();
                    }
                } finally {
                    connection.rollback();
                }
            }

            // 3. read-only 트랜잭션은 primary를 보장한다고 가정하지 않고 실제 역할을 기록한다.
            try (Connection connection = connect(proxy, null)) {
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                try {
                    boolean standby = isStandby(connection);
                    report(proxy, "read-only-transaction", standby);
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("Worker의 FOR UPDATE SKIP LOCKED 조회는 standby에서 거부되지 않는다")
    void workerLockingSelectUsesPrimary() throws SQLException {
        for (String proxy : PROXIES) {
            try (Connection connection = connect(proxy, null)) {
                // 1. 실제 Worker가 사용하는 세 테이블에서 빈 결과만 잠금 조회한다.
                for (String table : new String[] {"embedding_jobs", "sync_outbox_events", "rag_responses"}) {
                    try (Statement statement = connection.createStatement();
                         ResultSet result = statement.executeQuery(
                             "SELECT id FROM " + table + " WHERE 1 = 0 "
                                 + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED")) {
                        assertThat(result.next()).isFalse();
                    }
                    // 2. standby는 FOR UPDATE를 실행할 수 없으므로 성공 자체가 primary 라우팅 증거다.
                    System.out.printf("CONTRACT_LOCK proxy=%s table=%s route=primary%n", alias(proxy), table);
                }
            }
        }
    }

    @Test
    @Timeout(90)
    @DisplayName("기본·강제 server-side prepared statement를 여러 트랜잭션에서 반복한다")
    void preparedStatementsSurviveTransactionPooling() throws SQLException {
        for (String proxy : PROXIES) {
            for (int threshold : new int[] {5, 1}) {
                // 1. 같은 물리 JDBC 연결과 PreparedStatement를 유지한 채 트랜잭션만 바꾼다.
                try (Connection connection = connect(proxy, threshold)) {
                    connection.setAutoCommit(false);
                    Set<String> backends = new HashSet<>();
                    long preparedOnLastBackend = 0;
                    try (PreparedStatement statement = connection.prepareStatement("SELECT ?::integer")) {
                        PGStatement pgStatement = statement.unwrap(PGStatement.class);
                        assertThat(pgStatement.getPrepareThreshold()).isEqualTo(threshold);
                        for (int value = 1; value <= 15; value++) {
                            statement.setInt(1, value);
                            try (ResultSet result = statement.executeQuery()) {
                                assertThat(result.next()).isTrue();
                                assertThat(result.getInt(1)).isEqualTo(value);
                            }
                            backends.add(backendIdentity(connection));
                            if (value == 15) {
                                // 2. 같은 트랜잭션에 묶어 pg_prepared_statements를 같은 backend에서 읽는다.
                                preparedOnLastBackend = queryLong(connection,
                                    "SELECT count(*) FROM pg_prepared_statements");
                            }
                            connection.commit();
                        }
                        // 3. 드라이버가 server-side prepare 단계에 들어갔는지 확인한다.
                        assertThat(pgStatement.isUseServerPrepare()).isTrue();
                    } finally {
                        connection.rollback();
                    }
                    System.out.printf("CONTRACT_PREPARED proxy=%s threshold=%d executions=15 "
                            + "distinct_backends=%d prepared_on_last_backend=%d%n",
                        alias(proxy), threshold, backends.size(), preparedOnLastBackend);
                }
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("timestamp와 timestamptz의 한국 시간 자정 경계를 보존한다")
    void timeZoneBoundaryIsStableAcrossProxies() throws SQLException {
        LocalDateTime wallClock = LocalDateTime.of(2026, 9, 27, 0, 0, 1);
        OffsetDateTime instant = wallClock.atOffset(ZoneOffset.ofHours(9));
        String baseline = null;
        for (String proxy : PROXIES) {
            try (Connection connection = connect(proxy, null)) {
                // 1. timestamp는 벽시계 시간을, timestamptz는 같은 순간을 돌려줘야 한다.
                String timeZone = queryString(connection, "SHOW TimeZone");
                if (baseline == null) {
                    baseline = timeZone;
                } else {
                    assertThat(timeZone).isEqualTo(baseline);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT ?::timestamp, ?::timestamptz")) {
                    statement.setObject(1, wallClock);
                    statement.setObject(2, instant);
                    try (ResultSet result = statement.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getObject(1, LocalDateTime.class)).isEqualTo(wallClock);
                        assertThat(result.getObject(2, OffsetDateTime.class).toInstant())
                            .isEqualTo(instant.toInstant());
                    }
                }

                // 2. SET LOCAL은 트랜잭션 밖 다음 요청으로 새지 않아야 한다.
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL TIME ZONE 'Pacific/Honolulu'");
                    assertThat(queryString(connection, "SHOW TimeZone")).isEqualTo("Pacific/Honolulu");
                } finally {
                    connection.rollback();
                }
                connection.setAutoCommit(true);
                assertThat(queryString(connection, "SHOW TimeZone")).isEqualTo(timeZone);
                System.out.printf("CONTRACT_TIMEZONE proxy=%s setting=%s boundary=pass%n",
                    alias(proxy), timeZone);
            }
        }
    }

    private Connection connect(String name, Integer threshold) throws SQLException {
        String url = System.getenv(name);
        if (threshold != null) {
            url += (url.contains("?") ? "&" : "?") + "prepareThreshold=" + threshold;
        }
        return DriverManager.getConnection(url, System.getenv("OPENSQL_APP_USER"),
            System.getenv("OPENSQL_APP_PASSWORD"));
    }

    private boolean isStandby(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT pg_is_in_recovery()")) {
            assertThat(result.next()).isTrue();
            return result.getBoolean(1);
        }
    }

    private String backendIdentity(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT pg_postmaster_start_time()::text || ':' || pg_backend_pid()")) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private void report(String proxy, String operation, boolean standby) {
        System.out.printf("CONTRACT_ROUTE proxy=%s operation=%s role=%s%n",
            alias(proxy), operation, standby ? "standby" : "primary");
    }

    private String alias(String proxy) {
        return proxy.equals(PROXIES[0]) ? "proxy-a" : "proxy-b";
    }
}
