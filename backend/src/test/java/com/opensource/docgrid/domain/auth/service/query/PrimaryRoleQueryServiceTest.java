package com.opensource.docgrid.domain.auth.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.user.repository.UserRoleRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

/**
 * 관리자 역할 조회가 primary 확인 전에는 역할 SQL을 실행하지 않는 경계를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PrimaryRoleQueryService 단위 테스트")
class PrimaryRoleQueryServiceTest {

    @InjectMocks private PrimaryRoleQueryService service;
    @Mock private EntityManager entityManager;
    @Mock private Query query;
    @Mock private UserRoleRepository userRoleRepository;

    @Test
    @DisplayName("primary에서만 현재 역할을 반환한다")
    void findCurrentRoles_readsRolesOnPrimary() {
        given(entityManager.createNativeQuery("SELECT pg_is_in_recovery()"))
            .willReturn(query);
        given(query.getSingleResult()).willReturn(false);
        given(userRoleRepository.findRoleCodesByUserId(1L)).willReturn(List.of("USER"));

        assertThat(service.findCurrentRoles(1L)).containsExactly("USER");
    }

    @Test
    @DisplayName("standby라면 오래된 ADMIN을 조회하지 않고 실패한다")
    void findCurrentRoles_rejectsStandby() {
        given(entityManager.createNativeQuery("SELECT pg_is_in_recovery()"))
            .willReturn(query);
        given(query.getSingleResult()).willReturn(true);

        assertThatThrownBy(() -> service.findCurrentRoles(1L))
            .isInstanceOf(IllegalStateException.class);
        then(userRoleRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("한 push의 ADMIN 후보를 primary에서 일괄 조회한다")
    void findCurrentAdminUserIds_readsOnlyCurrentAdminsOnPrimary() {
        given(entityManager.createNativeQuery("SELECT pg_is_in_recovery()"))
            .willReturn(query);
        given(query.getSingleResult()).willReturn(false);
        given(userRoleRepository.findAdminUserIdsByUserIdIn(List.of(1L, 2L)))
            .willReturn(List.of(1L));

        assertThat(service.findCurrentAdminUserIds(List.of(1L, 2L))).isEqualTo(Set.of(1L));
        then(userRoleRepository).should().findAdminUserIdsByUserIdIn(List.of(1L, 2L));
    }

    @Test
    @DisplayName("standby 또는 primary 확인 실패 시 일괄 역할 SQL을 실행하지 않는다")
    void findCurrentAdminUserIds_rejectsStandby() {
        given(entityManager.createNativeQuery("SELECT pg_is_in_recovery()"))
            .willReturn(query);
        given(query.getSingleResult()).willReturn(true);

        assertThatThrownBy(() -> service.findCurrentAdminUserIds(List.of(1L)))
            .isInstanceOf(IllegalStateException.class);
        then(userRoleRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("후보가 없으면 primary와 역할 SQL 모두 호출하지 않는다")
    void findCurrentAdminUserIds_skipsQueriesForEmptyCandidates() {
        assertThat(service.findCurrentAdminUserIds(List.of())).isEmpty();
        then(entityManager).shouldHaveNoInteractions();
        then(userRoleRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("500명을 넘는 후보는 같은 primary 트랜잭션에서 제한된 크기로 나눠 조회한다")
    void findCurrentAdminUserIds_splitsLargeCandidateSet() {
        List<Long> userIds = LongStream.rangeClosed(1, 501).boxed().toList();
        given(entityManager.createNativeQuery("SELECT pg_is_in_recovery()"))
            .willReturn(query);
        given(query.getSingleResult()).willReturn(false);
        given(userRoleRepository.findAdminUserIdsByUserIdIn(userIds.subList(0, 500)))
            .willReturn(List.of(1L));
        given(userRoleRepository.findAdminUserIdsByUserIdIn(userIds.subList(500, 501)))
            .willReturn(List.of(501L));

        assertThat(service.findCurrentAdminUserIds(userIds)).isEqualTo(Set.of(1L, 501L));
        then(userRoleRepository).should().findAdminUserIdsByUserIdIn(userIds.subList(0, 500));
        then(userRoleRepository).should().findAdminUserIdsByUserIdIn(userIds.subList(500, 501));
    }

    @Test
    @DisplayName("호출자의 read-only 경계를 상속하지 않는 새 트랜잭션을 요구한다")
    void findCurrentRoles_startsIndependentReadWriteTransaction() throws NoSuchMethodException {
        Transactional transaction = PrimaryRoleQueryService.class
            .getMethod("findCurrentRoles", Long.class).getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isFalse();
        assertThat(transaction.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
