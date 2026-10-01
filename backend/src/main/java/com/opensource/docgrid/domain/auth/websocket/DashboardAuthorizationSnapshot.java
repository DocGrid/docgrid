package com.opensource.docgrid.domain.auth.websocket;

import java.util.Map;
import java.util.Set;

/**
 * 대시보드 push 한 번에 대해 후보 물리 세션과 primary에서 확인한 ADMIN 사용자만 내부 메시지에 전달한다.
 *
 * <p>브로커가 복제한 수신자별 MESSAGE에서만 읽으며 클라이언트 native STOMP 헤더에는 싣지 않는다.
 * 다른 push에 재사용하지 않고, 로그에 사용자 ID가 노출되지 않도록 문자열 표현도 제한한다.
 */
public record DashboardAuthorizationSnapshot(Map<String, Long> candidateSessions, Set<Long> adminUserIds) {

    public static final String HEADER = "docgrid.dashboard.authorizationSnapshot";

    public DashboardAuthorizationSnapshot {
        candidateSessions = Map.copyOf(candidateSessions);
        adminUserIds = Set.copyOf(adminUserIds);
        if (!Set.copyOf(candidateSessions.values()).containsAll(adminUserIds)) {
            throw new IllegalArgumentException("ADMIN 판정은 이번 push의 후보 사용자에 한정해야 합니다.");
        }
    }

    public boolean wasCandidate(String sessionId, Long userId) {
        return userId.equals(candidateSessions.get(sessionId));
    }

    public boolean allows(String sessionId, Long userId) {
        return wasCandidate(sessionId, userId) && adminUserIds.contains(userId);
    }

    @Override
    public String toString() {
        return "DashboardAuthorizationSnapshot[redacted]";
    }
}
