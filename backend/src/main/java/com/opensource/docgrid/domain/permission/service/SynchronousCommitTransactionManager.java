package com.opensource.docgrid.domain.permission.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 명시적으로 표시한 권한 회수 COMMIT의 물리 연결 경고를 풀 정리 전에 판정한다.
 * 경고가 있는 로컬 커밋을 원격 확정 성공으로 반환하지 않으며 다른 트랜잭션에는 기존 동작을 유지한다.
 */
public class SynchronousCommitTransactionManager extends JpaTransactionManager {
    private static final int COMMIT_NETWORK_TIMEOUT_MS = 5000;
    private static final ThreadLocal<Boolean> GUARDED = new ThreadLocal<>();

    public SynchronousCommitTransactionManager(EntityManagerFactory factory, DataSource dataSource) {
        super(factory);
        setDataSource(dataSource);
    }

    /** 현재 호출의 새 트랜잭션 COMMIT만 판정하고 예외 경로에서도 표식을 정리한다. */
    public static <T> T guarded(Supplier<T> operation) {
        Boolean previous = GUARDED.get();
        GUARDED.set(true);
        try { return operation.get(); }
        finally {
            if (previous == null) GUARDED.remove();
            else GUARDED.set(previous);
        }
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        if (!Boolean.TRUE.equals(GUARDED.get())) {
            super.doCommit(status);
            return;
        }
        // 1. JPA Transaction과 같은 연결만 사용한다. 새 연결로 경고를 추정하지 않는다.
        Object resource = TransactionSynchronizationManager.getResource(getDataSource());
        if (!(resource instanceof ConnectionHolder holder)) {
            throw new TransactionSystemException("Synchronous commit connection is unavailable");
        }
        Connection connection = holder.getConnection();
        int previousTimeout;
        try { previousTimeout = connection.getNetworkTimeout(); }
        catch (SQLException exception) { throw new TransactionSystemException("Commit deadline is unavailable", exception); }
        try {
            // COMMIT의 SyncRep 대기는 statement_timeout만으로 끝나지 않을 수 있다.
            connection.setNetworkTimeout(Runnable::run, COMMIT_NETWORK_TIMEOUT_MS);
            // 2. 이전 SQL의 경고를 제거한 뒤 실제 COMMIT을 수행한다.
            connection.clearWarnings();
            super.doCommit(status);
            // 3. PostgreSQL의 SyncRep 취소는 예외 대신 SQLWarning일 수 있다.
            if (connection.getWarnings() != null) {
                throw new TransactionSystemException("Synchronous commit outcome is unconfirmed");
            }
        } catch (SQLException exception) {
            // 물리 커밋 뒤 경고 조회도 실패할 수 있으므로 성공을 추정하지 않는다.
            throw new TransactionSystemException("Synchronous commit outcome is unconfirmed", exception);
        } finally {
            try {
                if (!connection.isClosed()) connection.setNetworkTimeout(Runnable::run, previousTimeout);
            } catch (SQLException exception) {
                // 복구할 수 없는 연결은 성공한 풀 연결로 재사용하지 않는다.
                try { connection.close(); } catch (SQLException ignored) { }
            }
        }
    }
}
