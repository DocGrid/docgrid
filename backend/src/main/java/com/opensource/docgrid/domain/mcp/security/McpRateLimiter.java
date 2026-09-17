package com.opensource.docgrid.domain.mcp.security;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * MCP 도구 호출 빈도를 사용자·도구 단위로 제한한다. 서버 단일 인스턴스를 전제로 한
 * 인메모리 고정 윈도우(fixed window) 카운터이며, Redis 등 외부 저장소를 쓰지 않는다.
 *
 * "고정 윈도우"란: 60초짜리 시간 구간을 하나 정해두고, 그 구간 안에서 호출 횟수를 센다.
 * 60초가 지나면 그 구간은 "만료"되고, 카운트는 0부터 다시 시작한다.
 * (이 제한은 "평생 총 N번"이 아니라 "매 1분마다 N번씩 다시 허용"하는 속도 제한이다.)
 *
 * <p>사용자·도구 조합별 카운터({@link Window})는 {@link Cache}(Caffeine)에 보관하며, 마지막
 * 접근으로부터 일정 시간(기본 2분)이 지나면 자동으로 제거된다(#292) — 한 번이라도 호출된
 * 조합이 서버 재시작 전까지 메모리에 영구히 쌓이는 것을 막기 위함.
 */
@Component
public class McpRateLimiter {

    // 하나의 카운트 구간(윈도우) 길이 = 60초 = 60,000ms
    private static final long WINDOW_MILLIS = 60_000;

    /**
     * 마지막 접근으로부터 이 시간이 지난 사용자·도구 조합은 캐시에서 자동 제거된다(#292).
     *
     * <p>rate limit 윈도우(60초)보다 넉넉하게(2배) 잡아, 아직 활동 중인 조합이 애매한
     * 타이밍에 지워지는 일이 없도록 여유를 둔다.
     */
    private static final long TTL_MILLIS = TimeUnit.MINUTES.toMillis(2);

    // "사용자+도구 조합 하나"당 관리해야 하는 카운트 구간 정보를 담는 그릇
    private static final class Window {
        private long windowStartMillis; // 이 구간이 언제 시작됐는지 (이 시각으로부터 60초가 지나면 만료)
        private int count; // 이 구간이 시작된 뒤로 지금까지 호출된 횟수

        private Window(long windowStartMillis) {
            this.windowStartMillis = windowStartMillis;
        }
    }

    /**
     * key = {@code "userId:toolName"}(예: {@code "5:search_documents"}) → 그 조합 전용
     * {@link Window}. 사용자별·도구별로 완전히 독립된 카운터를 갖게 된다.
     */
    private final Cache<String, Window> windows;

    private final long windowMillis;

    // 운영 환경에서 Spring이 빈을 만들 때 호출되는 생성자 — 윈도우 길이는 항상 60초로 고정
    public McpRateLimiter() {
        this(WINDOW_MILLIS, TTL_MILLIS);
    }

    /**
     * 테스트에서 윈도우 만료 경계를 짧은 시간 안에 재현할 수 있도록 window 길이를 주입받는다.
     * 실제로 60초를 기다릴 수 없으니, 테스트에서만 예: 100ms처럼 짧은 값을 넣어 빠르게 검증한다.
     */
    McpRateLimiter(long windowMillis) {
        this(windowMillis, TTL_MILLIS);
    }

    // 테스트에서 TTL 자동 제거를 짧은 시간 안에 재현할 수 있도록 TTL도 함께 주입받는다.
    McpRateLimiter(long windowMillis, long ttlMillis) {
        this.windowMillis = windowMillis;
        this.windows = Caffeine.newBuilder()
                .expireAfterAccess(ttlMillis, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * 사용자·도구 단위 호출 제한을 검사한다. 제한을 초과하면 DocGridException을 던진다.
     *
     * <p>구간 만료 판단·리셋·카운트 증가를 {@code synchronized(window)} 하나로 묶는 이유(과거
     * 레이스 컨디션 이력)는 아래 {@code synchronized} 블록 위 주석 참고.
     *
     * @param userId         사용자 ID
     * @param toolName       도구 이름
     * @param limitPerMinute 분당 호출 제한 횟수
     */
    public void checkLimit(Long userId, String toolName, int limitPerMinute) {
        String key = userId + ":" + toolName;
        long now = System.currentTimeMillis();
        Window window = windows.get(key, k -> new Window(now));

        /*
         * 만료 판단·리셋·카운트 증가를 synchronized(window) 하나로 묶어야 하는 이유 — 과거엔
         * windowStartMillis/count를 AtomicLong/AtomicInteger로 따로 관리해서 레이스가 있었다.
         * 예: 20/20 다 쓴 직후, 윈도우가 막 만료된 순간에 두 요청(21·22번째)이 겹치면:
         *   1) 스레드A(21번째)가 만료를 감지해 windowStart만 새 시각으로 갱신 — count=0은 아직 실행 전
         *   2) 그 틈에 스레드B(22번째)가 들어와 "안 만료됨"으로 오판(리셋 스킵) → 옛 count(20)에 증가
         *      → 21 > 20 → 새 윈도우의 첫 요청인데 부당하게 차단됨
         *   3) 뒤늦게 스레드A가 count=0 실행 → 스레드B가 방금 남긴 증가(21)까지 통째로 사라짐
         * synchronized(window)로 판단+리셋+증가를 한 덩어리로 묶으면 이 틈 자체가 사라진다.
         */
        synchronized (window) {
            if (now - window.windowStartMillis >= windowMillis) {
                window.windowStartMillis = now;
                window.count = 0;
            }
            window.count++;
            if (window.count > limitPerMinute) {
                throw new DocGridException(ErrorCode.RATE_LIMIT_EXCEEDED);
            }
        }
    }

    /**
     * 테스트 전용 — TTL 만료로 캐시에서 실제로 제거됐는지 확인한다.
     *
     * <p>{@code cleanUp()}은 Caffeine이 백그라운드 스레드 없이 다음 접근 시점에야 만료를
     * 정리하는 지연(lazy) 청소 방식이라, 검증 전에 명시적으로 호출해 즉시 정리를 강제한다.
     */
    long size() {
        windows.cleanUp();
        return windows.estimatedSize();
    }
}