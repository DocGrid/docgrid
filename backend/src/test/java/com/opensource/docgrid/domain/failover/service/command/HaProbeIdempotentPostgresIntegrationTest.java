package com.opensource.docgrid.domain.failover.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies the V45 PostgreSQL uniqueness and separate-statement replay mechanism.
 *
 * <p>This opt-in test uses only a caller-supplied local PostgreSQL database with V45 already
 * migrated; it does not start the app, touch GCP, or claim to test HTTP failover.
 */
@Tag("integration")
class HaProbeIdempotentPostgresIntegrationTest {

    private static final String INSERT = """
        INSERT INTO ha_probe_idempotent_writes (run_id, request_id, payload)
        VALUES (?, ?, ?)
        ON CONFLICT (run_id, request_id) DO NOTHING
        """;

    @Test
    void losingTransactionSeesWinnerAfterWaitingForItsCommit() throws Exception {
        String url = System.getenv("HA_PROBE_TEST_JDBC_URL");
        assumeTrue(url != null && url.startsWith("jdbc:postgresql://127.0.0.1:"),
            "명시적으로 지정한 로컬 PostgreSQL에서만 실행합니다");
        String runId = "ha777" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String requestId = runId + "-v1-i1";
        var loserReady = new CountDownLatch(1);
        var loserPid = new AtomicInteger();
        var pool = Executors.newSingleThreadExecutor();
        try (Connection winner = DriverManager.getConnection(url)) {
            // 1. Keep the winner uncommitted while the other connection reaches the unique key.
            winner.setAutoCommit(false);
            assertThat(insert(winner, runId, requestId, "payload-1")).isEqualTo(1);
            var losingResult = pool.submit(() -> {
                try (Connection loser = DriverManager.getConnection(url)) {
                    loser.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                    loser.setAutoCommit(false);
                    try (PreparedStatement pidQuery = loser.prepareStatement("SELECT pg_backend_pid()");
                         ResultSet pidRow = pidQuery.executeQuery()) {
                        assertThat(pidRow.next()).isTrue();
                        loserPid.set(pidRow.getInt(1));
                    }
                    loserReady.countDown();
                    int inserted = insert(loser, runId, requestId, "payload-1");
                    String payload = storedPayload(loser, runId, requestId);
                    loser.commit();
                    return inserted == 0 && "payload-1".equals(payload);
                }
            });
            assertThat(loserReady.await(5, TimeUnit.SECONDS)).isTrue();
            // 2. Observe the loser actually waiting on PostgreSQL's conflicting transaction.
            assertThat(waitForLock(url, loserPid.get())).isTrue();
            winner.commit();
            // 3. Its next SELECT must see the committed winner within the same transaction.
            assertThat(losingResult.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
            deleteRun(url, runId);
        }
    }

    @Test
    void replayReadsCommittedWinnerInTheSameReadCommittedTransaction() throws Exception {
        String url = System.getenv("HA_PROBE_TEST_JDBC_URL");
        assumeTrue(url != null && url.startsWith("jdbc:postgresql://127.0.0.1:"),
            "명시적으로 지정한 로컬 PostgreSQL에서만 실행합니다");
        String runId = "ha777" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String requestId = runId + "-v1-i1";
        try {
            // 1. A previously committed write represents an uncertain first HTTP response.
            try (Connection first = DriverManager.getConnection(url)) {
                assertThat(insert(first, runId, requestId, "payload-1")).isEqualTo(1);
            }
            // 2. The conflict and following SELECT run inside one READ COMMITTED transaction.
            try (Connection replay = DriverManager.getConnection(url)) {
                replay.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                replay.setAutoCommit(false);
                assertThat(insert(replay, runId, requestId, "payload-1")).isZero();
                assertThat(storedPayload(replay, runId, requestId)).isEqualTo("payload-1");
                // 3. A different payload must be visible as a conflict without a second row.
                assertThat(insert(replay, runId, requestId, "payload-2")).isZero();
                assertThat(storedPayload(replay, runId, requestId)).isEqualTo("payload-1");
                replay.commit();
            }
            try (Connection check = DriverManager.getConnection(url);
                 PreparedStatement statement = check.prepareStatement("""
                     SELECT count(*) FROM ha_probe_idempotent_writes
                     WHERE run_id = ? AND request_id = ?
                     """)) {
                statement.setString(1, runId);
                statement.setString(2, requestId);
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(1);
                }
            }
        } finally {
            deleteRun(url, runId);
        }
    }

    @Test
    void concurrentAppConnectionsProduceOneRowAndReplaySeesTheWinner() throws Exception {
        String url = System.getenv("HA_PROBE_TEST_JDBC_URL");
        assumeTrue(url != null && url.startsWith("jdbc:postgresql://127.0.0.1:"),
            "명시적으로 지정한 로컬 PostgreSQL에서만 실행합니다");
        String runId = "ha777" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String requestId = runId + "-v1-i1";
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> concurrentInsert(url, runId, requestId, ready, start));
            var second = pool.submit(() -> concurrentInsert(url, runId, requestId, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            try (Connection connection = DriverManager.getConnection(url)) {
                // A lost response followed by the same ID reaches the conflict/read branch.
                assertThat(insert(connection, runId, requestId, "payload-1")).isZero();
                assertThat(insert(connection, runId, requestId, "payload-2")).isZero();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT count(*), min(payload) FROM ha_probe_idempotent_writes
                        WHERE run_id = ? AND request_id = ?
                        """)) {
                    statement.setString(1, runId);
                    statement.setString(2, requestId);
                    try (ResultSet rows = statement.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).isEqualTo(1);
                        assertThat(rows.getString(2)).isEqualTo("payload-1");
                    }
                }
            }
        } finally {
            pool.shutdownNow();
            deleteRun(url, runId);
        }
    }

    private int concurrentInsert(String url, String runId, String requestId,
                                 CountDownLatch ready, CountDownLatch start) throws Exception {
        try (Connection connection = DriverManager.getConnection(url)) {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 시작 관문이 열리지 않았습니다");
            }
            return insert(connection, runId, requestId, "payload-1");
        }
    }

    private int insert(Connection connection, String runId, String requestId, String payload)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, runId);
            statement.setString(2, requestId);
            statement.setString(3, payload);
            return statement.executeUpdate();
        }
    }

    private String storedPayload(Connection connection, String runId, String requestId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT payload FROM ha_probe_idempotent_writes
                WHERE run_id = ? AND request_id = ?
                """)) {
            statement.setString(1, runId);
            statement.setString(2, requestId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private boolean waitForLock(String url, int backendPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (Connection observer = DriverManager.getConnection(url);
             PreparedStatement statement = observer.prepareStatement("""
                 SELECT wait_event_type = 'Lock' FROM pg_stat_activity WHERE pid = ?
                 """)) {
            statement.setInt(1, backendPid);
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next() && rows.getBoolean(1)) {
                        return true;
                    }
                }
                Thread.sleep(25);
            }
            return false;
        }
    }

    private void deleteRun(String url, String runId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
             PreparedStatement statement = connection.prepareStatement(
                 "DELETE FROM ha_probe_idempotent_writes WHERE run_id = ?")) {
            statement.setString(1, runId);
            statement.executeUpdate();
        }
    }
}
