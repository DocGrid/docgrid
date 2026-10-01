package com.opensource.docgrid.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import com.opensource.docgrid.domain.auth.jwt.StompAuthChannelInterceptor;
import com.opensource.docgrid.domain.auth.websocket.StompDestinationAuthorizationInterceptor;
import com.opensource.docgrid.domain.auth.websocket.StompDashboardOutboundAuthorizationInterceptor;
import com.opensource.docgrid.domain.auth.websocket.StompSessionTrackingDecoratorFactory;

import lombok.RequiredArgsConstructor;

/**
 * RAGOps Dashboard 및 RAG 답변 실시간 push를 위한 STOMP endpoint와 Message Broker 설정.
 *
 * <p>인증·인가는 이 설정이 아니라 {@link StompAuthChannelInterceptor}(CONNECT 시점 인증)와
 * {@link StompDestinationAuthorizationInterceptor}(SUBSCRIBE·SEND 시점 인가)가 담당한다.
 * 기존 구독에 나가는 대시보드 메시지는 {@link StompDashboardOutboundAuthorizationInterceptor}가
 * 현재 역할을 다시 확인한다. 이 클래스는 전송 계층 구성(endpoint·broker·origin), 물리 세션 추적과
 * Interceptor 등록만 책임진다.
 *
 * <p>{@code /queue}는 RAG 답변 개인 알림({@code convertAndSendToUser})의 broker 내부 목적지로 쓰인다.
 * client는 {@code /user/queue/rag-answer}만 구독할 수 있고, 실제 {@code /queue}와 pattern 접근은
 * {@code StompDestinationAuthorizationInterceptor}가 broker에 도달하기 전에 거부한다.
 */
@EnableWebSocketMessageBroker
@Configuration
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final StompDestinationAuthorizationInterceptor stompDestinationAuthorizationInterceptor;
    private final StompDashboardOutboundAuthorizationInterceptor stompDashboardOutboundAuthorizationInterceptor;
    private final StompSessionTrackingDecoratorFactory stompSessionTrackingDecoratorFactory;

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
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // CONNECT 전에 생성되는 물리 세션을 보관해야 이후 인증 snapshot과 같은 sessionId로 결합할 수 있다.
        registration.addDecoratorFactory(stompSessionTrackingDecoratorFactory);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 1. StompAuthChannelInterceptor가 CONNECT 프레임의 JWT를 검증하고 세션에 Principal을 부착한다.
        // 2. StompDestinationAuthorizationInterceptor가 그 Principal로 SUBSCRIBE·SEND 권한을 검증한다.
        //    순서가 바뀌면 2번 시점에 Principal이 아직 없어 항상 거부된다.
        registration.interceptors(stompAuthChannelInterceptor, stompDestinationAuthorizationInterceptor);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        // 브로커가 기존 구독자에게 보내는 매 메시지는 실제 전달 전에 최신 관리자 역할을 확인한다.
        registration.interceptors(stompDashboardOutboundAuthorizationInterceptor);
    }
}
