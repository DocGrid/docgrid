package com.opensource.docgrid.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import com.opensource.docgrid.domain.auth.jwt.StompAuthChannelInterceptor;
import com.opensource.docgrid.domain.auth.websocket.StompDestinationAuthorizationInterceptor;

import lombok.RequiredArgsConstructor;

/**
 * RAGOps Dashboard 및 RAG 답변 실시간 push를 위한 STOMP endpoint와 Message Broker 설정.
 *
 * <p>인증·인가는 이 설정이 아니라 {@link StompAuthChannelInterceptor}(CONNECT 시점 인증)와
 * {@link StompDestinationAuthorizationInterceptor}(SUBSCRIBE·SEND 시점 인가)가 담당한다.
 * 이 클래스는 전송 계층 구성(endpoint·broker·origin)과 두 Interceptor의 등록 순서만 책임진다.
 *
 * <p>{@code /queue}는 RAG 답변 개인 알림({@code convertAndSendToUser})에 쓰인다. 대시보드처럼
 * 별도 구독 인가 Interceptor가 없는 이유는 {@code RagWebSocketController} 문서 참고 — 사용자별
 * 격리가 Spring의 user destination 메커니즘 자체로 이미 보장된다.
 */
@EnableWebSocketMessageBroker
@Configuration
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final StompDestinationAuthorizationInterceptor stompDestinationAuthorizationInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOrigins(CorsConfig.ALLOWED_ORIGINS.toArray(new String[0]))
            .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 1. StompAuthChannelInterceptor가 CONNECT 프레임의 JWT를 검증하고 세션에 Principal을 부착한다.
        // 2. StompDestinationAuthorizationInterceptor가 그 Principal로 SUBSCRIBE·SEND 권한을 검증한다.
        //    순서가 바뀌면 2번 시점에 Principal이 아직 없어 항상 거부된다.
        registration.interceptors(stompAuthChannelInterceptor, stompDestinationAuthorizationInterceptor);
    }
}
