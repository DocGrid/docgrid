package com.opensource.docgrid.domain.rag.controller;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * RAG 완료 ID만 공유 Redis에 전파하고 각 앱의 로컬 STOMP broker에 배달하는 경계.
 *
 * <p>이메일·답변 본문은 Pub/Sub에 싣지 않는다. 수신 앱은 DB에서 불변인 query 소유자를 확인한
 * 뒤 자기 세션에만 알린다. Pub/Sub는 영속 큐가 아니므로 연결 단절 중 놓친 신호는 프런트의
 * 기존 REST 폴링이 수렴시키며, 이 신호의 실패가 확정된 RAG 상태를 뒤집지 않는다.
 */
@Slf4j
@Component
public class RagAnswerCrossNodeSignal implements MessageListener {

    public static final String CHANNEL = "docgrid:rag:answer-ready:v1";

    private final StringRedisTemplate redisTemplate;
    private final SearchQueryRepository searchQueryRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final boolean enabled;
    private final String instanceId = UUID.randomUUID().toString();

    public RagAnswerCrossNodeSignal(
        StringRedisTemplate redisTemplate,
        SearchQueryRepository searchQueryRepository,
        SimpMessagingTemplate messagingTemplate,
        @Value("${rag.answer.cross-node-enabled:false}") boolean enabled
    ) {
        this.redisTemplate = redisTemplate;
        this.searchQueryRepository = searchQueryRepository;
        this.messagingTemplate = messagingTemplate;
        this.enabled = enabled;
    }

    /** 로컬 전송과 DB 완료가 끝난 뒤 원격 앱에 신호만 전한다. */
    public void publish(Long queryId) {
        if (!enabled || queryId == null || queryId <= 0) {
            return;
        }
        try {
            // 1. 개인정보와 답변 내용 대신 발행자·query ID만 전송한다.
            redisTemplate.convertAndSend(CHANNEL, instanceId + ":" + queryId);
        } catch (RuntimeException exception) {
            log.warn("RAG 교차 백엔드 완료 신호 발행 실패: cause={}", exception.getClass().getSimpleName());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (!enabled) {
            return;
        }
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        String[] parts = payload.split(":", -1);
        if (parts.length != 2 || parts[0].length() != 36 || parts[1].length() > 19
            || !parts[1].matches("[1-9][0-9]*")) {
            return;
        }
        try {
            String origin = UUID.fromString(parts[0]).toString();
            if (instanceId.equals(origin)) {
                return;
            }
            long queryId = Long.parseLong(parts[1]);
            // 2. 수신자는 payload를 수신자로 믿지 않고 DB의 실제 query 소유자를 확인한다.
            Optional<String> userEmail = searchQueryRepository.findUserEmailByQueryId(queryId);
            // 3. 현재 앱의 개인 queue로만 트리거를 보낸다. 답변은 클라이언트가 REST로 재조회한다.
            userEmail.ifPresent(email -> messagingTemplate.convertAndSendToUser(
                email, RagWebSocketController.RAG_ANSWER_QUEUE,
                new RagWebSocketController.RagAnswerReadyEvent(queryId)
            ));
        } catch (IllegalArgumentException exception) {
            // 형식이 잘못된 메시지는 다른 사용자에게 전달하지 않는다.
        } catch (RuntimeException exception) {
            log.warn("RAG 교차 백엔드 완료 신호 처리 실패: cause={}", exception.getClass().getSimpleName());
        }
    }
}
