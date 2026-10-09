package com.opensource.docgrid.domain.rag.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/** RAG 완료 알림이 공유 신호와 현재 앱의 개인 queue 양쪽으로 나가는 계약을 검증한다. */
class RagWebSocketControllerTest {

    @Test
    void completionNotifiesRemoteNodesAndLocalUser() {
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        RagAnswerCrossNodeSignal crossNodeSignal = mock(RagAnswerCrossNodeSignal.class);
        RagWebSocketController controller = new RagWebSocketController(messagingTemplate, crossNodeSignal);

        controller.notifyAnswerReady("owner@test.invalid", 41L);

        verify(crossNodeSignal).publish(41L);
        verify(messagingTemplate).convertAndSendToUser(
            eq("owner@test.invalid"), eq(RagWebSocketController.RAG_ANSWER_QUEUE),
            eq(new RagWebSocketController.RagAnswerReadyEvent(41L))
        );
    }
}
