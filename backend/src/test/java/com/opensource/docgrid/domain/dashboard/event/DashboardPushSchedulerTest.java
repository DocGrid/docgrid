package com.opensource.docgrid.domain.dashboard.event;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.dashboard.controller.DashboardWebSocketController;
import com.opensource.docgrid.domain.dashboard.dto.response.DashboardSummaryResponse;
import com.opensource.docgrid.domain.dashboard.service.query.DashboardQueryService;

@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardPushScheduler 단위 테스트")
class DashboardPushSchedulerTest {

    @InjectMocks private DashboardPushScheduler scheduler;

    @Mock private DashboardUpdateFlag dashboardUpdateFlag;
    @Mock private DashboardQueryService dashboardQueryService;
    @Mock private DashboardWebSocketController dashboardWebSocketController;

    @Test
    @DisplayName("정상 케이스: 플래그가 dirty였으면 집계를 계산해서 push한다")
    void pushIfDirty_computesAndPushes_whenFlagWasDirty() {
        // Given
        DashboardSummaryResponse summary = mock(DashboardSummaryResponse.class);
        given(dashboardUpdateFlag.consumeIfDirty()).willReturn(true);
        given(dashboardQueryService.getSummary()).willReturn(summary);

        // When
        scheduler.pushIfDirty();

        // Then
        then(dashboardWebSocketController).should().sendDashboardUpdate(summary);
    }

    @Test
    @DisplayName("예외 케이스: 플래그가 dirty가 아니었으면 집계도 push도 하지 않는다")
    void pushIfDirty_doesNothing_whenFlagWasNotDirty() {
        // Given
        given(dashboardUpdateFlag.consumeIfDirty()).willReturn(false);

        // When
        scheduler.pushIfDirty();

        // Then
        then(dashboardQueryService).shouldHaveNoInteractions();
        then(dashboardWebSocketController).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("예외 케이스: 집계·push 도중 예외가 나면 dirty 플래그를 복구하고 예외를 다시 던진다")
    void pushIfDirty_restoresDirtyFlagAndRethrows_whenSendingUpdateFails() {
        // Given
        given(dashboardUpdateFlag.consumeIfDirty()).willReturn(true);
        given(dashboardQueryService.getSummary()).willThrow(new RuntimeException("집계 실패"));

        // When & Then
        assertThatThrownBy(() -> scheduler.pushIfDirty())
            .isInstanceOf(RuntimeException.class)
            .hasMessage("집계 실패");
        then(dashboardUpdateFlag).should().markDirty();
    }

    @Test
    @DisplayName("primary 판정 실패로 push하지 못하면 dirty 신호를 복구해 다음 주기에 재시도한다")
    void pushIfDirty_restoresDirtyFlag_whenAuthorizationFails() {
        DashboardSummaryResponse summary = mock(DashboardSummaryResponse.class);
        given(dashboardUpdateFlag.consumeIfDirty()).willReturn(true);
        given(dashboardQueryService.getSummary()).willReturn(summary);
        willThrow(new IllegalStateException("primary unavailable"))
            .given(dashboardWebSocketController).sendDashboardUpdate(summary);

        assertThatThrownBy(scheduler::pushIfDirty)
            .isInstanceOf(IllegalStateException.class);
        then(dashboardUpdateFlag).should().markDirty();
    }

    @Test
    @DisplayName("원격 신호만 있으면 자기 구독자에게 push하고 Redis로 재발행하지 않는다")
    void pushIfDirty_sendsLocallyWithoutRepublishing_whenRemoteDirty() {
        DashboardSummaryResponse summary = mock(DashboardSummaryResponse.class);
        given(dashboardUpdateFlag.consumeRemoteIfDirty()).willReturn(true);
        given(dashboardQueryService.getSummary()).willReturn(summary);

        scheduler.pushIfDirty();

        then(dashboardWebSocketController).should().sendLocalDashboardUpdate(summary);
        then(dashboardWebSocketController).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("로컬과 원격 신호가 겹치면 한 번 계산하고 로컬 push 경로로 한 번 보낸다")
    void pushIfDirty_coalescesLocalAndRemoteDirty() {
        DashboardSummaryResponse summary = mock(DashboardSummaryResponse.class);
        given(dashboardUpdateFlag.consumeIfDirty()).willReturn(true);
        given(dashboardUpdateFlag.consumeRemoteIfDirty()).willReturn(true);
        given(dashboardQueryService.getSummary()).willReturn(summary);

        scheduler.pushIfDirty();

        then(dashboardQueryService).should().getSummary();
        then(dashboardWebSocketController).should().sendDashboardUpdate(summary);
        then(dashboardWebSocketController).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("원격 push가 실패하면 원격 신호를 복구하고 다음 주기에 재시도한다")
    void pushIfDirty_restoresRemoteDirty_whenRemoteSendFails() {
        DashboardSummaryResponse summary = mock(DashboardSummaryResponse.class);
        given(dashboardUpdateFlag.consumeRemoteIfDirty()).willReturn(true);
        given(dashboardQueryService.getSummary()).willReturn(summary);
        willThrow(new IllegalStateException("primary unavailable"))
            .given(dashboardWebSocketController).sendLocalDashboardUpdate(summary);

        assertThatThrownBy(scheduler::pushIfDirty)
            .isInstanceOf(IllegalStateException.class);
        then(dashboardUpdateFlag).should().markRemoteDirty();
    }
}
