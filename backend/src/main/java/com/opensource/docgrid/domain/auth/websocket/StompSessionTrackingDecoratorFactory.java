package com.opensource.docgrid.domain.auth.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import lombok.RequiredArgsConstructor;

/**
 * Spring STOMP 계층 밖의 물리 WebSocketSession을 수명주기 registry에 연결하는 전송 계층 adapter다.
 *
 * <p>Spring 6.2의 STOMP sessionId는 이 WebSocketSession id에서 만들어지므로 CONNECT interceptor가
 * 같은 식별자로 인증 snapshot을 결합할 수 있다. 연결 설정이 실패하거나 정상 종료돼도 registry
 * entry가 남지 않도록 delegate 호출의 실패·종료 경계에서 정리한다.
 */
@Component
@RequiredArgsConstructor
public class StompSessionTrackingDecoratorFactory implements WebSocketHandlerDecoratorFactory {

    private final StompSessionRegistry stompSessionRegistry;

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                // 1. STOMP CONNECT보다 물리 연결이 먼저 생기므로 transport를 먼저 등록한다.
                stompSessionRegistry.registerTransport(session);
                try {
                    super.afterConnectionEstablished(session);
                } catch (Exception exception) {
                    stompSessionRegistry.remove(session.getId());
                    throw exception;
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status)
                throws Exception {
                try {
                    super.afterConnectionClosed(session, status);
                } finally {
                    // 2. 네트워크 종료와 서버 강제 종료 모두 같은 경로에서 추적 정보를 제거한다.
                    stompSessionRegistry.remove(session.getId());
                }
            }
        };
    }
}
