package com.opensource.docgrid.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.opensource.docgrid.domain.dashboard.event.DashboardCrossNodeSignal;

/** Redis 공유 구성이 켜진 백엔드에서만 대시보드 갱신 채널을 구독한다. */
@Configuration
public class DashboardCrossNodeSignalConfig {

    @Bean
    @ConditionalOnProperty(prefix = "dashboard.push", name = "cross-node-enabled", havingValue = "true")
    RedisMessageListenerContainer dashboardSignalListenerContainer(
        RedisConnectionFactory connectionFactory,
        DashboardCrossNodeSignal signal
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(signal, new ChannelTopic(DashboardCrossNodeSignal.CHANNEL));
        return container;
    }
}
