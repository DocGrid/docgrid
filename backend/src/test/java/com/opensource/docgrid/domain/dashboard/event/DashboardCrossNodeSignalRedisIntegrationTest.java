package com.opensource.docgrid.domain.dashboard.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** 두 백엔드의 Redis 구독·자기 echo 무시·재구독 갱신을 실제 Redis 연결로 검증한다. */
@Tag("integration")
@DisplayName("대시보드 교차 백엔드 Redis Pub/Sub 통합 테스트")
class DashboardCrossNodeSignalRedisIntegrationTest {

    @Test
    @DisplayName("A/B 신호가 반대편에만 도착하고 재구독 시 놓친 상태를 다시 읽는다")
    void crossNodeSignal_reachesPeerAndRefreshesAfterReconnect() throws Exception {
        String port = System.getenv("DOCGRID_TEST_REDIS_PORT");
        assumeTrue(port != null, "전용 시험 Redis 포트가 없으면 이 통합 테스트를 건너뜁니다.");

        LettuceConnectionFactory aConnection = connectionFactory(port);
        LettuceConnectionFactory bConnection = connectionFactory(port);
        DashboardUpdateFlag aFlag = new DashboardUpdateFlag();
        DashboardUpdateFlag bFlag = new DashboardUpdateFlag();
        DashboardCrossNodeSignal a = new DashboardCrossNodeSignal(new StringRedisTemplate(aConnection), aFlag, true);
        DashboardCrossNodeSignal b = new DashboardCrossNodeSignal(new StringRedisTemplate(bConnection), bFlag, true);
        RedisMessageListenerContainer aListener = container(aConnection, a);
        RedisMessageListenerContainer bListener = container(bConnection, b);
        try {
            // 1. 실제 구독 완료를 기다리고 최초 동기화 신호를 비운다.
            aListener.start();
            bListener.start();
            awaitSubscribed(aListener);
            awaitSubscribed(bListener);
            assertThat(awaitDirty(aFlag)).isTrue();
            assertThat(awaitDirty(bFlag)).isTrue();

            // 2. A/B 각 발행은 상대방에만 반영되고 자기 echo는 새 push를 만들지 않는다.
            a.publish();
            assertThat(awaitDirty(bFlag)).isTrue();
            assertThat(aFlag.consumeRemoteIfDirty()).isFalse();
            b.publish();
            assertThat(awaitDirty(aFlag)).isTrue();
            assertThat(bFlag.consumeRemoteIfDirty()).isFalse();

            // 3. B가 구독 중단 중 놓친 신호는 재구독 callback이 최신 snapshot 재계산으로 보상한다.
            bListener.stop();
            a.publish();
            assertThat(bFlag.consumeRemoteIfDirty()).isFalse();
            bListener.start();
            awaitSubscribed(bListener);
            assertThat(awaitDirty(bFlag)).isTrue();
        } finally {
            aListener.stop();
            bListener.stop();
            aListener.destroy();
            bListener.destroy();
            aConnection.destroy();
            bConnection.destroy();
        }
    }

    private LettuceConnectionFactory connectionFactory(String port) {
        LettuceConnectionFactory connection = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(port));
        connection.afterPropertiesSet();
        return connection;
    }

    private RedisMessageListenerContainer container(
        LettuceConnectionFactory connectionFactory,
        DashboardCrossNodeSignal signal
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(signal, new ChannelTopic(DashboardCrossNodeSignal.CHANNEL));
        container.afterPropertiesSet();
        return container;
    }

    private void awaitSubscribed(RedisMessageListenerContainer container) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!container.isListening() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(container.isListening()).isTrue();
    }

    private boolean awaitDirty(DashboardUpdateFlag flag) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (flag.consumeRemoteIfDirty()) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }
}
