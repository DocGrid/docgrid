package com.opensource.docgrid.domain.auth.websocket;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.auth.jwt.TokenBlacklistService;
import com.opensource.docgrid.domain.auth.websocket.StompSessionRegistry.SessionSnapshot;
import com.opensource.docgrid.domain.user.entity.UserRole;
import com.opensource.docgrid.domain.user.repository.UserRoleRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 열린 STOMP 세션의 token 만료·blacklist·현재 역할을 주기적으로 일괄 재검증한다.
 *
 * <p>각 Backend 인스턴스는 자신이 보유한 물리 세션만 검사한다. blacklist는 Redis MGET, 역할은
 * userId IN query로 batch 조회해 세션 수만큼 외부 호출이 늘어나는 것을 막는다. Redis 또는 DB 상태를
 * 확인할 수 없으면 WebSocket을 fail-closed로 종료하며 프런트는 기존 REST polling으로 전환한다.
 */
@Component
@Slf4j
public class StompSessionRevalidationScheduler {

    private static final String CLOSED_METRIC = "docgrid.stomp.sessions.closed";

    private final StompSessionRegistry stompSessionRegistry;
    private final TokenBlacklistService tokenBlacklistService;
    private final UserRoleRepository userRoleRepository;
    private final Clock clock;
    private final int batchSize;
    private final Map<CloseReason, Counter> closeCounters;

    public StompSessionRevalidationScheduler(
        StompSessionRegistry stompSessionRegistry,
        TokenBlacklistService tokenBlacklistService,
        UserRoleRepository userRoleRepository,
        Clock clock,
        MeterRegistry meterRegistry,
        @Value("${auth.stomp.session-revalidation.batch-size:500}") int batchSize
    ) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("STOMP 세션 재검증 batch-size는 1 이상이어야 합니다.");
        }
        this.stompSessionRegistry = stompSessionRegistry;
        this.tokenBlacklistService = tokenBlacklistService;
        this.userRoleRepository = userRoleRepository;
        this.clock = clock;
        this.batchSize = batchSize;
        this.closeCounters = registerCloseCounters(meterRegistry);

        Gauge.builder(
            "docgrid.stomp.sessions.active",
            stompSessionRegistry,
            StompSessionRegistry::authenticatedSessionCount
        ).description("Authenticated STOMP WebSocket sessions owned by this backend instance")
            .register(meterRegistry);
    }

    @Scheduled(
        fixedDelayString = "${auth.stomp.session-revalidation.interval:5s}",
        initialDelayString = "${auth.stomp.session-revalidation.interval:5s}"
    )
    public void revalidate() {
        List<SessionSnapshot> sessions = stompSessionRegistry.authenticatedSessions();
        if (sessions.isEmpty()) {
            return;
        }

        // 1. JWT 만료는 외부 조회 없이 먼저 제거해 Redis·DB 검사 대상을 줄인다.
        Instant now = clock.instant();
        List<SessionSnapshot> candidates = new ArrayList<>();
        for (SessionSnapshot session : sessions) {
            if (session.authorization().isExpired(now)) {
                close(session, CloseReason.EXPIRED);
            } else {
                candidates.add(session);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }

        // 2. Redis와 DB 중 하나라도 검증할 수 없으면 기존 연결도 신규 CONNECT와 같이 fail-closed한다.
        try {
            Set<String> blacklistedJtis = findBlacklistedJtis(candidates);
            Map<Long, Set<String>> currentRoles = findCurrentRoles(candidates);

            // 3. token 폐기와 역할 snapshot 변경을 같은 검사 주기에서 확정한다.
            for (SessionSnapshot session : candidates) {
                StompSessionAuthorization authorization = session.authorization();
                if (blacklistedJtis.contains(authorization.jti())) {
                    close(session, CloseReason.BLACKLISTED);
                    continue;
                }
                Set<String> roles = currentRoles.getOrDefault(authorization.userId(), Set.of());
                if (!authorization.roles().equals(roles)) {
                    close(session, CloseReason.ROLES_CHANGED);
                }
            }
        } catch (RuntimeException exception) {
            int closed = closeAll(candidates, CloseReason.VALIDATION_FAILED);
            log.error(
                "STOMP 세션 인증 상태를 확인할 수 없어 연결을 종료했습니다. closed={}: {}",
                closed,
                exception.getMessage()
            );
        }
    }

    private Set<String> findBlacklistedJtis(List<SessionSnapshot> sessions) {
        List<String> jtis = sessions.stream()
            .map(session -> session.authorization().jti())
            .distinct()
            .toList();
        Set<String> blacklisted = new HashSet<>();
        for (List<String> batch : batches(jtis)) {
            blacklisted.addAll(tokenBlacklistService.findBlacklistedJtis(batch));
        }
        return blacklisted;
    }

    private Map<Long, Set<String>> findCurrentRoles(List<SessionSnapshot> sessions) {
        List<Long> userIds = sessions.stream()
            .map(session -> session.authorization().userId())
            .distinct()
            .toList();
        Map<Long, Set<String>> rolesByUserId = new HashMap<>();
        for (List<Long> batch : batches(userIds)) {
            List<UserRole> userRoles = userRoleRepository.findAllWithRoleByUserIdIn(batch);
            for (UserRole userRole : userRoles) {
                rolesByUserId.computeIfAbsent(userRole.getUser().getId(), ignored -> new HashSet<>())
                    .add(userRole.getRole().getCode());
            }
        }
        rolesByUserId.replaceAll((ignored, roles) -> Set.copyOf(roles));
        return rolesByUserId;
    }

    private <T> List<List<T>> batches(List<T> values) {
        List<List<T>> batches = new ArrayList<>();
        for (int start = 0; start < values.size(); start += batchSize) {
            batches.add(values.subList(start, Math.min(start + batchSize, values.size())));
        }
        return batches;
    }

    private int closeAll(List<SessionSnapshot> sessions, CloseReason reason) {
        int closed = 0;
        for (SessionSnapshot session : sessions) {
            if (close(session, reason)) {
                closed++;
            }
        }
        return closed;
    }

    private boolean close(SessionSnapshot session, CloseReason reason) {
        boolean closed = stompSessionRegistry.close(session.sessionId());
        if (closed) {
            closeCounters.get(reason).increment();
        }
        return closed;
    }

    private Map<CloseReason, Counter> registerCloseCounters(MeterRegistry meterRegistry) {
        Map<CloseReason, Counter> counters = new EnumMap<>(CloseReason.class);
        for (CloseReason reason : CloseReason.values()) {
            counters.put(
                reason,
                Counter.builder(CLOSED_METRIC)
                    .description("STOMP sessions closed after authorization revalidation")
                    .tag("reason", reason.label)
                    .register(meterRegistry)
            );
        }
        return counters;
    }

    /** Metric tag를 고정된 네 값으로 제한하는 세션 종료 분류다. */
    private enum CloseReason {
        EXPIRED("expired"),
        BLACKLISTED("blacklisted"),
        ROLES_CHANGED("roles_changed"),
        VALIDATION_FAILED("validation_failed");

        private final String label;

        CloseReason(String label) {
            this.label = label;
        }
    }
}
