package com.opensource.docgrid.domain.dashboard.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis 신호가 비밀 데이터 없이 전달되고 자기 echo가 다시 push를 만들지 않는지 검증한다. */
@DisplayName("대시보드 교차 백엔드 갱신 신호 단위 테스트")
class DashboardCrossNodeSignalTest {

    private static final byte[] CHANNEL = DashboardCrossNodeSignal.CHANNEL.getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("활성화하면 고정 채널에 임시 인스턴스 ID만 발행하고 자기 echo는 무시한다")
    void publish_onlyInstanceIdAndIgnoreOwnEcho() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        DashboardUpdateFlag flag = mock(DashboardUpdateFlag.class);
        DashboardCrossNodeSignal signal = new DashboardCrossNodeSignal(redis, flag, true);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);

        signal.publish();

        then(redis).should().convertAndSend(eq(DashboardCrossNodeSignal.CHANNEL), body.capture());
        assertThat(body.getValue()).matches("[0-9a-f-]{36}");
        signal.onMessage(new DefaultMessage(CHANNEL, body.getValue().getBytes(StandardCharsets.UTF_8)), null);
        then(flag).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("다른 백엔드 신호는 원격 dirty만 세우며 재발행하지 않는다")
    void onMessage_marksRemoteDirtyWithoutPublishing() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        DashboardUpdateFlag flag = mock(DashboardUpdateFlag.class);
        DashboardCrossNodeSignal signal = new DashboardCrossNodeSignal(redis, flag, true);

        signal.onMessage(new DefaultMessage(CHANNEL,
            "11111111-1111-1111-1111-111111111111".getBytes(StandardCharsets.UTF_8)), null);

        then(flag).should().markRemoteDirty();
        then(redis).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Redis 재구독 때는 끊긴 동안 놓친 변경을 다시 읽도록 표시한다")
    void onChannelSubscribed_marksRemoteDirty() {
        DashboardUpdateFlag flag = mock(DashboardUpdateFlag.class);
        DashboardCrossNodeSignal signal = new DashboardCrossNodeSignal(mock(StringRedisTemplate.class), flag, true);

        signal.onChannelSubscribed(CHANNEL, 1);

        then(flag).should().markRemoteDirty();
    }

    @Test
    @DisplayName("비활성 상태나 Redis 발행 오류가 기존 로컬 push를 실패시키지 않는다")
    void publish_doesNotThrowWhenDisabledOrRedisFails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        new DashboardCrossNodeSignal(redis, mock(DashboardUpdateFlag.class), false).publish();
        then(redis).shouldHaveNoInteractions();

        given(redis.convertAndSend(eq(DashboardCrossNodeSignal.CHANNEL),
            org.mockito.ArgumentMatchers.any(String.class)))
            .willThrow(new IllegalStateException("Redis unavailable"));
        new DashboardCrossNodeSignal(redis, mock(DashboardUpdateFlag.class), true).publish();
    }
}
