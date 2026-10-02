package com.opensource.docgrid.domain.dashboard.event;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 대시보드 내용 대신 갱신 신호만 Redis로 전파하는 백엔드 간 경계.
 *
 * <p>수신자는 자기 DB에서 새 요약을 읽고 자기 WebSocket 세션만 다시 인가한다. 원격 수신은
 * {@link DashboardUpdateFlag}만 세우므로 신호가 다시 Redis로 발행되는 루프가 생기지 않는다.
 * Pub/Sub는 내구성 있는 큐가 아니므로 일시적으로 연결이 끊겼다가 재구독할 때 한 번 다시 갱신한다.
 */
@Slf4j
@Component
public class DashboardCrossNodeSignal implements MessageListener, SubscriptionListener {

    public static final String CHANNEL = "docgrid:dashboard:refresh:v1";

    private final StringRedisTemplate redisTemplate;
    private final DashboardUpdateFlag dashboardUpdateFlag;
    private final boolean enabled;
    private final String instanceId = UUID.randomUUID().toString();

    public DashboardCrossNodeSignal(
        StringRedisTemplate redisTemplate,
        DashboardUpdateFlag dashboardUpdateFlag,
        @Value("${dashboard.push.cross-node-enabled:false}") boolean enabled
    ) {
        this.redisTemplate = redisTemplate;
        this.dashboardUpdateFlag = dashboardUpdateFlag;
        this.enabled = enabled;
    }

    /** 로컬 push의 성공 여부와 무관하게 다른 백엔드에도 갱신 기회를 알린다. */
    public void publish() {
        if (!enabled) {
            return;
        }
        try {
            // 내용·사용자 ID·내부 주소는 보내지 않는다. 응답 실패는 이미 확정된 DB 변경을 뒤집지 않는다.
            redisTemplate.convertAndSend(CHANNEL, instanceId);
        } catch (RuntimeException exception) {
            log.warn("대시보드 교차 백엔드 갱신 신호 발행 실패: cause={}",
                exception.getClass().getSimpleName());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        // 자기 발행의 Redis echo와 형식이 다른 외부 메시지는 처리하지 않는다.
        if (message.getBody().length != 36) {
            return;
        }
        String origin = new String(message.getBody(), StandardCharsets.UTF_8);
        if (!instanceId.equals(origin)) {
            dashboardUpdateFlag.markRemoteDirty();
        }
    }

    @Override
    public void onChannelSubscribed(byte[] channel, long count) {
        // 재연결 중 놓친 신호가 있어도 현재 구독자에게 최신 DB snapshot을 한 번 다시 보낸다.
        dashboardUpdateFlag.markRemoteDirty();
    }
}
