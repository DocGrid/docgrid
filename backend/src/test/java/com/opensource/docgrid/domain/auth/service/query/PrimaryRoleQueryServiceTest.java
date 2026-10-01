package com.opensource.docgrid.domain.auth.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;

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
    @DisplayName("호출자의 read-only 경계를 상속하지 않는 새 트랜잭션을 요구한다")
    void findCurrentRoles_startsIndependentReadWriteTransaction() throws NoSuchMethodException {
        Transactional transaction = PrimaryRoleQueryService.class
            .getMethod("findCurrentRoles", Long.class).getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isFalse();
        assertThat(transaction.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
