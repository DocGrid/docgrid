package com.opensource.docgrid.domain.auth.service.query;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.user.repository.UserRoleRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * HTTP 관리자 인가와 대시보드 push에 필요한 역할을 Redis나 standby 없이 현재 primary에서 확인한다.
 *
 * <p>호출자의 read-only 트랜잭션을 상속하지 않으며, OpenProxy가 잘못 라우팅하면
 * 권한을 추정하지 않고 요청을 실패시킨다. 일반 API·WebSocket의 역할 캐시는 담당하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PrimaryRoleQueryService {

    private static final int MAX_BATCH_SIZE = 500;

    private final EntityManager entityManager;
    private final UserRoleRepository userRoleRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<String> findCurrentRoles(Long userId) {
        // 1. 명시적 read-write 트랜잭션 안에서 OpenProxy의 실제 도착 역할을 확인한다.
        requirePrimary();

        // 2. 같은 트랜잭션의 최신 primary에서 역할을 조회한다.
        return userRoleRepository.findRoleCodesByUserId(userId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Set<Long> findCurrentAdminUserIds(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }

        // 1. 단일 read-write 트랜잭션에서 primary를 확인해 standby의 낡은 ADMIN을 쓰지 않는다.
        requirePrimary();

        // 2. 큰 세션 집합도 크기가 제한된 IN 쿼리로 읽되, 결과는 이 push에만 쓰는 불변 집합으로 만든다.
        Set<Long> admins = new HashSet<>();
        for (int start = 0; start < userIds.size(); start += MAX_BATCH_SIZE) {
            admins.addAll(userRoleRepository.findAdminUserIdsByUserIdIn(
                userIds.subList(start, Math.min(start + MAX_BATCH_SIZE, userIds.size()))
            ));
        }
        return Set.copyOf(admins);
    }

    private void requirePrimary() {
        Object inRecovery = entityManager.createNativeQuery("SELECT pg_is_in_recovery()")
            .getSingleResult();
        if (!Boolean.FALSE.equals(inRecovery)) {
            throw new IllegalStateException("Primary role verification is unavailable");
        }
    }
}
