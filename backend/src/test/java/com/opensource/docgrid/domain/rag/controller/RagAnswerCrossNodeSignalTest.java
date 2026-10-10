package com.opensource.docgrid.domain.rag.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;

/** RAG 완료 신호가 앱 사이에서 소유자만 찾아 전달되고 자기 echo는 중복시키지 않는지 검증한다. */
class RagAnswerCrossNodeSignalTest {

    private StringRedisTemplate redisTemplate;
    private SearchQueryRepository searchQueryRepository;
    private SimpMessagingTemplate messagingTemplate;
    private RagAnswerCrossNodeSignal origin;
    private RagAnswerCrossNodeSignal receiver;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        searchQueryRepository = mock(SearchQueryRepository.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        origin = new RagAnswerCrossNodeSignal(redisTemplate, searchQueryRepository, messagingTemplate, true);
        receiver = new RagAnswerCrossNodeSignal(redisTemplate, searchQueryRepository, messagingTemplate, true);
    }

    @Test
    void remoteNodeUsesDatabaseOwnerAndDeliversOnlyToThatUser() {
        origin.publish(41L);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(eq(RagAnswerCrossNodeSignal.CHANNEL), payload.capture());
        when(searchQueryRepository.findUserEmailByQueryId(41L)).thenReturn(Optional.of("owner@test.invalid"));

        receiver.onMessage(message(payload.getValue()), null);

        verify(messagingTemplate).convertAndSendToUser(
            eq("owner@test.invalid"), eq(RagWebSocketController.RAG_ANSWER_QUEUE),
            eq(new RagWebSocketController.RagAnswerReadyEvent(41L))
        );
        verify(searchQueryRepository).findUserEmailByQueryId(41L);
    }

    @Test
    void ownRedisEchoDoesNotDuplicateLocalNotification() {
        origin.publish(41L);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(eq(RagAnswerCrossNodeSignal.CHANNEL), payload.capture());

        origin.onMessage(message(payload.getValue()), null);

        verify(searchQueryRepository, never()).findUserEmailByQueryId(any());
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    void missingOwnerAndInvalidPayloadAreNotDelivered() {
        when(searchQueryRepository.findUserEmailByQueryId(41L)).thenReturn(Optional.empty());

        receiver.onMessage(message("not-a-signal"), null);
        receiver.onMessage(message("00000000-0000-0000-0000-000000000000:41"), null);

        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
        verify(searchQueryRepository).findUserEmailByQueryId(41L);
    }

    @Test
    void disabledModePublishesAndDeliversNothing() {
        RagAnswerCrossNodeSignal disabled = new RagAnswerCrossNodeSignal(
            redisTemplate, searchQueryRepository, messagingTemplate, false
        );

        disabled.publish(41L);
        disabled.onMessage(message("00000000-0000-0000-0000-000000000000:41"), null);

        verify(redisTemplate, never()).convertAndSend(any(), any());
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    private Message message(String body) {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
