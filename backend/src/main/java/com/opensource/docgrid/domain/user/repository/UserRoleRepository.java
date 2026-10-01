package com.opensource.docgrid.domain.user.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.user.entity.UserRole;

public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

    @Query("SELECT ur FROM UserRole ur JOIN FETCH ur.role WHERE ur.user.id = :userId")
    List<UserRole> findAllWithRoleByUserId(@Param("userId") Long userId);

    /**
     * 사용자 페이지에 포함된 역할을 한 번에 조회해 사용자별 역할 N+1을 방지한다.
     */
    @Query("""
            SELECT ur
            FROM UserRole ur
            JOIN FETCH ur.user
            JOIN FETCH ur.role role
            WHERE ur.user.id IN :userIds
            ORDER BY role.code ASC
            """)
    List<UserRole> findAllWithRoleByUserIdIn(@Param("userIds") List<Long> userIds);

    @Query("SELECT ur.role.code FROM UserRole ur WHERE ur.user.id = :userId")
    List<String> findRoleCodesByUserId(@Param("userId") Long userId);

    /** 한 대시보드 push의 후보 사용자 중 현재 ADMIN인 ID만 primary 트랜잭션에 반환한다. */
    @Query("SELECT DISTINCT ur.user.id FROM UserRole ur WHERE ur.user.id IN :userIds AND ur.role.code = 'ADMIN'")
    List<Long> findAdminUserIdsByUserIdIn(@Param("userIds") List<Long> userIds);

    boolean existsByUserIdAndRoleCode(Long userId, String roleCode);

    Optional<UserRole> findByUserIdAndRoleCode(Long userId, String roleCode);
}
