package com.opensource.docgrid.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.opensource.docgrid.domain.rag.controller.RagAnswerCrossNodeSignal;

/** 공유 Redis 설정이 켜진 앱에서만 RAG 개인 알림의 원격 완료 채널을 구독한다. */
@Configuration
public class RagAnswerCrossNodeSignalConfig {

    @Bean
    @ConditionalOnProperty(prefix = "rag.answer", name = "cross-node-enabled", havingValue = "true")
    RedisMessageListenerContainer ragAnswerSignalListenerContainer(
        RedisConnectionFactory connectionFactory,
        RagAnswerCrossNodeSignal signal
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(signal, new ChannelTopic(RagAnswerCrossNodeSignal.CHANNEL));
        return container;
    }
}
