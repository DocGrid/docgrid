package com.opensource.docgrid.domain.auth.service.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.user.repository.UserRoleRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * HTTP 관리자 인가에 필요한 역할을 Redis나 standby 없이 현재 primary에서 확인한다.
 *
 * <p>호출자의 read-only 트랜잭션을 상속하지 않으며, OpenProxy가 잘못 라우팅하면
 * 권한을 추정하지 않고 요청을 실패시킨다. 일반 API·WebSocket의 역할 캐시는 담당하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PrimaryRoleQueryService {

    private final EntityManager entityManager;
    private final UserRoleRepository userRoleRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<String> findCurrentRoles(Long userId) {
        // 1. 명시적 read-write 트랜잭션 안에서 OpenProxy의 실제 도착 역할을 확인한다.
        Object inRecovery = entityManager.createNativeQuery("SELECT pg_is_in_recovery()")
            .getSingleResult();
        if (!Boolean.FALSE.equals(inRecovery)) {
            throw new IllegalStateException("Primary role verification is unavailable");
        }

        // 2. 같은 트랜잭션의 최신 primary에서 역할을 조회한다.
        return userRoleRepository.findRoleCodesByUserId(userId);
    }
}
