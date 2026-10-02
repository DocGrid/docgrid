package com.opensource.docgrid.domain.dashboard.controller;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.auth.service.query.PrimaryRoleQueryService;
import com.opensource.docgrid.domain.auth.websocket.DashboardAuthorizationSnapshot;
import com.opensource.docgrid.domain.auth.websocket.StompSessionRegistry;
import com.opensource.docgrid.domain.dashboard.dto.response.DashboardSummaryResponse;
import com.opensource.docgrid.domain.dashboard.event.DashboardCrossNodeSignal;

import lombok.RequiredArgsConstructor;

/**
 * RAGOps Dashboard 집계 지표를 {@code /topic/dashboard} 구독자에게 push하는 전송 계층.
 *
 * <p>이 컨트롤러는 직접 지표를 집계하지 않는다. 호출하는 쪽(재처리 트리거, 상태 전이 이벤트
 * 리스너 등)이 {@code DashboardQueryService}로 최신 Snapshot을 계산해 넘겨주면 그대로
 * 이 JVM의 구독자에게 보낸다. 로컬 발행은 다른 백엔드에 갱신 신호만 알리고,
 * 원격 신호로 인한 발행은 Redis로 재전파하지 않는다. 호출자가 요약을 먼저 계산한 뒤 이 메서드에서 권한을 판정해야,
 * 회수 전 판정으로 회수 후 새로 계산한 요약을 승인하지 않는다. 판정은 push 사이에 재사용하지 않는다.
 * 구독자별 DB 조회를 반복하지 않도록 각 push의 ADMIN 후보를 primary에서 일괄 확인한다.
 */
@Component
@RequiredArgsConstructor
public class DashboardWebSocketController {

    private static final String DASHBOARD_TOPIC = "/topic/dashboard";

    private final SimpMessagingTemplate messagingTemplate;
    private final StompSessionRegistry stompSessionRegistry;
    private final PrimaryRoleQueryService primaryRoleQueryService;
    private final DashboardCrossNodeSignal dashboardCrossNodeSignal;

    public void sendDashboardUpdate(DashboardSummaryResponse summary) {
        // 로컬 판정이 실패해도 다른 백엔드는 독립적으로 최신 요약을 계산할 기회를 얻는다.
        try {
            sendLocalDashboardUpdate(summary);
        } finally {
            dashboardCrossNodeSignal.publish();
        }
    }

    /** 원격 신호로 재계산한 요약도 이 JVM의 구독자에게만 보내며 Redis에 재발행하지 않는다. */
    public void sendLocalDashboardUpdate(DashboardSummaryResponse summary) {
        // 1. 이 백엔드의 열린 CONNECT-ADMIN 세션만 후보로 모으고 같은 사용자의 탭은 중복 제거한다.
        Map<String, Long> candidateSessions = stompSessionRegistry.authenticatedSessions().stream()
            .filter(session -> session.authorization().roles().contains("ADMIN"))
            .collect(Collectors.toUnmodifiableMap(session -> session.sessionId(),
                session -> session.authorization().userId()));
        List<Long> candidateUserIds = candidateSessions.values().stream()
            .distinct()
            .toList();

        // 2. 후보가 없으면 DB 트랜잭션도 열지 않는다. 실패 시에는 이전 권한으로 우회하지 않는다.
        DashboardAuthorizationSnapshot snapshot = new DashboardAuthorizationSnapshot(candidateSessions,
            candidateUserIds.isEmpty()
                ? Set.of()
                : primaryRoleQueryService.findCurrentAdminUserIds(candidateUserIds)
        );

        // 3. native STOMP 헤더가 아닌 서버 내부 헤더로만 이 push의 판정을 브로커에 전달한다.
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setHeader(DashboardAuthorizationSnapshot.HEADER, snapshot);
        headers.setLeaveMutable(true);
        messagingTemplate.convertAndSend(DASHBOARD_TOPIC, summary, headers.getMessageHeaders());
    }
}
