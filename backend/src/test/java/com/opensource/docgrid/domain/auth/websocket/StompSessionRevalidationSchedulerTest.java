package com.opensource.docgrid.domain.auth.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.auth.jwt.TokenBlacklistService;
import com.opensource.docgrid.domain.auth.websocket.StompSessionRegistry.SessionSnapshot;
import com.opensource.docgrid.domain.user.entity.Role;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.entity.UserRole;
import com.opensource.docgrid.domain.user.repository.UserRoleRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 열린 STOMP 세션의 만료·blacklist·역할 변경과 batch fail-closed 판단을 단위 수준에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("STOMP 세션 재검증 Scheduler 단위 테스트")
class StompSessionRevalidationSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @Mock private StompSessionRegistry stompSessionRegistry;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private UserRoleRepository userRoleRepository;

    private SimpleMeterRegistry meterRegistry;
    private StompSessionRevalidationScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new StompSessionRevalidationScheduler(
            stompSessionRegistry,
            tokenBlacklistService,
            userRoleRepository,
            Clock.fixed(NOW, ZoneOffset.UTC),
            meterRegistry,
            2
        );
    }

    @Test
    @DisplayName("만료된 세션은 외부 저장소를 조회하지 않고 종료한다")
    void revalidate_closesExpiredSession_withoutExternalLookup() {
        // Given
        SessionSnapshot expired = session("expired", 1L, "jti-1", NOW, "USER");
        given(stompSessionRegistry.authenticatedSessions()).willReturn(List.of(expired));
        given(stompSessionRegistry.close("expired")).willReturn(true);

        // When
        scheduler.revalidate();

        // Then
        then(stompSessionRegistry).should().close("expired");
        then(tokenBlacklistService).shouldHaveNoInteractions();
        then(userRoleRepository).shouldHaveNoInteractions();
        assertThat(closedCount("expired")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("blacklist에 등록된 세션만 종료하고 현재 역할이 같은 세션은 유지한다")
    void revalidate_closesBlacklistedSession_andKeepsValidSession() {
        // Given
        SessionSnapshot revoked = session("revoked", 1L, "jti-1", NOW.plusSeconds(60), "USER");
        SessionSnapshot valid = session("valid", 2L, "jti-2", NOW.plusSeconds(60), "USER");
        UserRole secondRole = userRole(2L, "USER");
        given(stompSessionRegistry.authenticatedSessions()).willReturn(List.of(revoked, valid));
        given(tokenBlacklistService.findBlacklistedJtis(List.of("jti-1", "jti-2")))
            .willReturn(Set.of("jti-1"));
        given(userRoleRepository.findAllWithRoleByUserIdIn(List.of(2L)))
            .willReturn(List.of(secondRole));
        given(stompSessionRegistry.close("revoked")).willReturn(true);

        // When
        scheduler.revalidate();

        // Then
        then(stompSessionRegistry).should().close("revoked");
        then(stompSessionRegistry).should(org.mockito.Mockito.never()).close("valid");
        then(userRoleRepository).should().findAllWithRoleByUserIdIn(List.of(2L));
        assertThat(closedCount("blacklisted")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("DB의 현재 역할이 연결 당시 snapshot과 다르면 세션을 종료한다")
    void revalidate_closesSession_whenRolesChanged() {
        // Given
        SessionSnapshot session = session("admin", 1L, "jti-1", NOW.plusSeconds(60), "ADMIN", "USER");
        UserRole currentRole = userRole(1L, "USER");
        given(stompSessionRegistry.authenticatedSessions()).willReturn(List.of(session));
        given(tokenBlacklistService.findBlacklistedJtis(List.of("jti-1"))).willReturn(Set.of());
        given(userRoleRepository.findAllWithRoleByUserIdIn(List.of(1L)))
            .willReturn(List.of(currentRole));
        given(stompSessionRegistry.close("admin")).willReturn(true);

        // When
        scheduler.revalidate();

        // Then
        then(stompSessionRegistry).should().close("admin");
        assertThat(closedCount("roles_changed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Redis 검증 실패 시 검사 대상 세션을 모두 fail-closed한다")
    void revalidate_closesAllCandidates_whenBlacklistLookupFails() {
        // Given
        SessionSnapshot first = session("first", 1L, "jti-1", NOW.plusSeconds(60), "USER");
        SessionSnapshot second = session("second", 2L, "jti-2", NOW.plusSeconds(60), "USER");
        given(stompSessionRegistry.authenticatedSessions()).willReturn(List.of(first, second));
        given(tokenBlacklistService.findBlacklistedJtis(List.of("jti-1", "jti-2")))
            .willThrow(new RuntimeException("redis down"));
        given(stompSessionRegistry.close("first")).willReturn(true);
        given(stompSessionRegistry.close("second")).willReturn(true);

        // When
        scheduler.revalidate();

        // Then
        then(stompSessionRegistry).should().close("first");
        then(stompSessionRegistry).should().close("second");
        then(userRoleRepository).shouldHaveNoInteractions();
        assertThat(closedCount("validation_failed")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("고유 token과 사용자가 batch-size를 넘으면 Redis와 DB를 같은 크기로 나눠 조회한다")
    void revalidate_chunksBlacklistAndRoleQueries() {
        // Given
        List<SessionSnapshot> sessions = List.of(
            session("one", 1L, "jti-1", NOW.plusSeconds(60), "USER"),
            session("two", 2L, "jti-2", NOW.plusSeconds(60), "USER"),
            session("three", 3L, "jti-3", NOW.plusSeconds(60), "USER")
        );
        UserRole firstRole = userRole(1L, "USER");
        UserRole secondRole = userRole(2L, "USER");
        UserRole thirdRole = userRole(3L, "USER");
        given(stompSessionRegistry.authenticatedSessions()).willReturn(sessions);
        given(tokenBlacklistService.findBlacklistedJtis(List.of("jti-1", "jti-2"))).willReturn(Set.of());
        given(tokenBlacklistService.findBlacklistedJtis(List.of("jti-3"))).willReturn(Set.of());
        given(userRoleRepository.findAllWithRoleByUserIdIn(List.of(1L, 2L)))
            .willReturn(List.of(firstRole, secondRole));
        given(userRoleRepository.findAllWithRoleByUserIdIn(List.of(3L)))
            .willReturn(List.of(thirdRole));

        // When
        scheduler.revalidate();

        // Then
        then(tokenBlacklistService).should().findBlacklistedJtis(List.of("jti-1", "jti-2"));
        then(tokenBlacklistService).should().findBlacklistedJtis(List.of("jti-3"));
        then(userRoleRepository).should().findAllWithRoleByUserIdIn(List.of(1L, 2L));
        then(userRoleRepository).should().findAllWithRoleByUserIdIn(List.of(3L));
        then(stompSessionRegistry).should(org.mockito.Mockito.never()).close(org.mockito.ArgumentMatchers.anyString());
    }

    private SessionSnapshot session(
        String sessionId,
        Long userId,
        String jti,
        Instant expiresAt,
        String... roles
    ) {
        return new SessionSnapshot(
            sessionId,
            new StompSessionAuthorization(userId, jti, expiresAt, Set.of(roles))
        );
    }

    private UserRole userRole(Long userId, String roleCode) {
        User user = mock(User.class);
        Role role = mock(Role.class);
        UserRole userRole = mock(UserRole.class);
        given(user.getId()).willReturn(userId);
        given(role.getCode()).willReturn(roleCode);
        given(userRole.getUser()).willReturn(user);
        given(userRole.getRole()).willReturn(role);
        return userRole;
    }

    private double closedCount(String reason) {
        return meterRegistry.find("docgrid.stomp.sessions.closed")
            .tag("reason", reason)
            .counter()
            .count();
    }
}
