package com.opensource.docgrid.domain.dashboard.event;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

/**
 * "대시보드 집계가 최신이 아니다"라는 사실을 로컬·원격 원인별로 기억하는 스레드 안전한 플래그.
 *
 * <p>여러 Worker Thread가 동시에 {@link #markDirty()}를 호출해도 안전하며, DB 조회나 WebSocket
 * push 같은 무거운 작업은 전혀 하지 않는다. 실제 집계 재계산과 push는 이 플래그를 주기적으로
 * 확인하는 스케줄러({@code DashboardPushScheduler})가 전담한다. 짧은 시간에 여러 상태 전이가
 * 몰려도(burst) 플래그는 계속 true로만 유지되므로, 스케줄러 입장에서는 몇 번 세워졌는지와 무관하게
 * "그 사이 뭔가 바뀌었다"는 사실 하나만 확인하면 된다 — 이 방식으로 이벤트 개수만큼 집계 쿼리가
 * 늘어나는 걸 막는다. 원격 신호는 별도 플래그로 유지해 다시 Redis에 발행하지 않는다.
 *
 * <p>실제 흐름 예시:
 * <pre>
 * [시작] dirty = false
 *
 * Worker가 job A를 claim → 리스너가 markDirty() 호출 → dirty = true
 * ... 0~300ms 사이 ...
 * 스케줄러가 300ms마다 도는 시점 → consumeIfDirty() 호출
 *   → dirty가 true였으니 true 반환, 동시에 dirty = false로 리셋
 *   → 스케줄러: "바뀐 게 있었네" → getSummary() 계산 → WebSocket push
 *
 * ... 다음 300ms, 아무도 markDirty()를 안 부른 경우 ...
 * 스케줄러가 다시 consumeIfDirty() 호출 → dirty가 false였으니 false 반환
 *   → 스케줄러: "바뀐 거 없네" → 아무것도 안 하고 다음 주기까지 대기
 * </pre>
 */
@Component
public class DashboardUpdateFlag {

    // 앱이 막 시작된 시점엔 아직 어떤 Job도 상태가 안 바뀌었으므로 "최신 상태"인 false로 시작한다.
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    // 다른 백엔드에서 온 신호는 로컬 갱신과 구분해 다시 Redis로 발행하지 않는다.
    private final AtomicBoolean remoteDirty = new AtomicBoolean(false);

    /**
     * "화면이 최신이 아니다"는 표시등을 켠다. {@code EmbeddingJobStatusChangedEventListener}가
     * Job 상태가 바뀔 때마다 호출한다. 이미 켜져 있는 상태에서 또 호출해도 결과는 그대로 켜진
     * 상태 하나뿐이라, burst로 여러 번 호출돼도 스케줄러 입장에서는 구분이 안 된다(의도된 동작).
     */
    public void markDirty() {
        dirty.set(true);
    }

    /**
     * 플래그가 서 있으면 원자적으로 내리면서 {@code true}를 반환한다.
     * {@code DashboardPushScheduler}가 debounce 주기마다 호출해서 "그 사이 뭔가 바뀌었는지"를
     * 확인하고, 바뀌었으면 그때 가서 집계를 다시 계산해 push한다.
     *
     * <p>"확인"과 "초기화"를 한 번의 원자 연산으로 묶어야, 스케줄러가 확인하는 순간과 다음 이벤트가
     * 플래그를 세우는 순간이 겹쳐도 변경 신호를 놓치지 않는다.
     */
    public boolean consumeIfDirty() {
        return dirty.compareAndSet(true, false);
    }

    /** 다른 백엔드의 갱신 신호를 다음 debounce 주기까지 합친다. */
    public void markRemoteDirty() {
        remoteDirty.set(true);
    }

    /** 원격 신호를 소비하되 로컬 변경 플래그와 섞지 않는다. */
    public boolean consumeRemoteIfDirty() {
        return remoteDirty.compareAndSet(true, false);
    }
}
