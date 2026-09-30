package com.opensource.docgrid.domain.user.service.command;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.opensource.docgrid.domain.auth.jwt.RoleAuthorityService;
import com.opensource.docgrid.domain.user.dto.request.AssignRoleRequest;
import com.opensource.docgrid.domain.user.dto.response.UserRoleResponse;
import com.opensource.docgrid.domain.user.entity.Role;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.entity.UserRole;
import com.opensource.docgrid.domain.user.repository.RoleRepository;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.domain.user.repository.UserRoleRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Transactional
@Service
@RequiredArgsConstructor
public class UserRoleCommandService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleAuthorityService roleAuthorityService;

    // 관리자가 다른 사용자에게 역할을 부여
    public UserRoleResponse assignRole(Long targetUserId, Long adminUserId, AssignRoleRequest request) {
        // 타겟 사용자 조회
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new DocGridException(ErrorCode.USER_NOT_FOUND));

        Role role = roleRepository.findByCode(request.roleCode())
                .orElseThrow(() -> new DocGridException(ErrorCode.ROLE_NOT_FOUND));

        if (userRoleRepository.existsByUserIdAndRoleCode(targetUserId, request.roleCode())) {
            throw new DocGridException(ErrorCode.ROLE_ALREADY_ASSIGNED);
        }

        User admin = userRepository.getReferenceById(adminUserId);

        UserRole userRole = UserRole.builder()
                .user(targetUser)
                .role(role)
                .assignedBy(admin) // 누가 역할을 부여했는지 기록
                .assignedAt(LocalDateTime.now())
                .build();
        userRoleRepository.save(userRole);
        invalidateAfterCommit(targetUserId);

        List<String> roles = userRoleRepository.findAllWithRoleByUserId(targetUserId).stream()
                .map(ur -> ur.getRole().getCode())
                .toList();

        return UserRoleResponse.of(targetUser, roles);
    }

    // 관리자가 다른 사용자에게 부여된 역할을 회수
    public UserRoleResponse revokeRole(Long targetUserId, String roleCode) {
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new DocGridException(ErrorCode.USER_NOT_FOUND));

        UserRole userRole = userRoleRepository.findByUserIdAndRoleCode(targetUserId, roleCode)
                .orElseThrow(() -> new DocGridException(ErrorCode.ROLE_NOT_ASSIGNED));

        userRoleRepository.delete(userRole);
        invalidateAfterCommit(targetUserId);

        List<String> roles = userRoleRepository.findAllWithRoleByUserId(targetUserId).stream()
                .map(ur -> ur.getRole().getCode())
                .toList();

        return UserRoleResponse.of(targetUser, roles);
    }

    // DB 커밋 전에 캐시를 지우면, 다른 요청이 아직 커밋 안 된 역할을 다시 읽을 수 있다.
    // 커밋 후 무효화하고, 조회·저장 사이에 끼어드는 요청은 Redis 세대 비교로 재캐시를 막는다.
    // 트랜잭션 밖에서 호출되는 경우(예: 단위 테스트)는 즉시 무효화한다.
    private void invalidateAfterCommit(Long userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    roleAuthorityService.invalidate(userId);
                }
            });
        } else {
            roleAuthorityService.invalidate(userId);
        }
    }
}
