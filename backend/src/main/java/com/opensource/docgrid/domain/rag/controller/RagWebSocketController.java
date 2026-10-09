package com.opensource.docgrid.domain.rag.controller;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * RAG 답변이 준비됐음을 요청한 사용자에게만 push하는 전송 계층 (#218).
 *
 * <p>{@code DashboardWebSocketController}(/topic/dashboard, 전체 브로드캐스트)와 달리, 이건 검색
 * 요청을 보낸 그 유저 한 명에게만 전달돼야 한다 — 관리자 전용 브로드캐스트 채널을 재사용할 수 없는
 * 이유다. {@code convertAndSendToUser}는 {@code StompAuthChannelInterceptor}가 CONNECT 시점에
 * 세션에 부착한 Principal(이메일)로 목적지를 사용자별로 격리한다. 여기에
 * {@code StompDestinationAuthorizationInterceptor}가 client의 정확한
 * {@code /user/queue/rag-answer} 구독만 허용하고 pattern과 broker 내부 queue 직접 접근을 차단한다.
 *
 * <p>본문은 트리거 용도로만 쓴다. {@code useDashboardSocket}과 동일하게, 프론트는 이 메시지를
 * "다시 조회해야 한다"는 신호로만 쓰고 최신 상태는 REST로 다시 읽는다 — Push 페이로드와 실제
 * DB 상태가 어긋날 걱정 없이 항상 단일 진실 소스(REST)를 신뢰할 수 있다.
 * 다른 앱에 {@link RagAnswerCrossNodeSignal}을 전파한 뒤 로컬 broker에도 보낸다. 한쪽 전송이
 * 실패해도 다른 쪽의 전달 기회를 잃지 않으며, 실제 답변은 REST 폴링으로도 수렴한다.
 */
@Component
@RequiredArgsConstructor
public class RagWebSocketController {

    static final String RAG_ANSWER_QUEUE = "/queue/rag-answer";

    private final SimpMessagingTemplate messagingTemplate;
    private final RagAnswerCrossNodeSignal crossNodeSignal;

    public void notifyAnswerReady(String userEmail, Long queryId) {
        // 1. 다른 앱의 세션은 공유 신호를 받아 자기 broker에서 별도로 알린다.
        crossNodeSignal.publish(queryId);
        // 2. Redis 발행이 실패해도 현재 앱의 로컬 세션에는 알린다.
        messagingTemplate.convertAndSendToUser(userEmail, RAG_ANSWER_QUEUE, new RagAnswerReadyEvent(queryId));
    }

    /**
     * 완료 알림의 최소 트리거 페이로드 — 답변 본문은 담지 않는다. 프론트가 이 이벤트를 받으면
     * 항상 GET /search/{queryId}로 다시 조회해야 하며, 이 record 자체를 최종 상태로 신뢰하면 안 된다.
     */
    record RagAnswerReadyEvent(Long queryId) {
    }
}
