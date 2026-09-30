package com.opensource.docgrid.domain.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import com.opensource.docgrid.domain.auth.service.query.PrimaryRoleQueryService;
import com.opensource.docgrid.domain.user.repository.UserRoleRepository;

/**
 * 인가 판단용 role을 Redis 캐시와 DB 폴백으로 조회하는 계약(캐시 히트/미스, 무효화, Redis 장애 대응)을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RoleAuthorityService 단위 테스트")
class RoleAuthorityServiceTest {

    @InjectMocks private RoleAuthorityService roleAuthorityService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private PrimaryRoleQueryService primaryRoleQueryService;

    @Test
    @DisplayName("캐시 히트: Redis에 값이 있으면 DB를 조회하지 않는다")
    void getRoles_returnsCachedRoles_whenCacheHit() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("auth:roles:1")).willReturn("USER,ADMIN");

        List<String> roles = roleAuthorityService.getRoles(1L);

        assertThat(roles).containsExactly("USER", "ADMIN");
        then(userRoleRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("캐시 미스: DB에서 조회한 뒤 Redis에 캐싱한다")
    void getRoles_fetchesFromDbAndCaches_whenCacheMiss() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("auth:roles:1")).willReturn(null);
        given(valueOperations.get("auth:roles:epoch:1")).willReturn(null);
        given(userRoleRepository.findRoleCodesByUserId(1L)).willReturn(List.of("USER"));
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
            .willReturn(1L);

        List<String> roles = roleAuthorityService.getRoles(1L);

        assertThat(roles).containsExactly("USER");
        then(redisTemplate).should().execute(any(RedisScript.class),
            org.mockito.ArgumentMatchers.eq(List.of("auth:roles:1", "auth:roles:epoch:1")),
            org.mockito.ArgumentMatchers.eq("0"), org.mockito.ArgumentMatchers.eq("USER"),
            org.mockito.ArgumentMatchers.eq("30"));
    }

    @Test
    @DisplayName("무효화 후에는 다시 DB를 조회한다")
    void invalidate_removesCacheKey() {
        roleAuthorityService.invalidate(1L);

        then(redisTemplate).should().execute(any(RedisScript.class),
            org.mockito.ArgumentMatchers.eq(List.of("auth:roles:1", "auth:roles:epoch:1")));
    }

    @Test
    @DisplayName("Redis 조회가 실패해도(장애) DB로 폴백해서 인증이 끊기지 않는다")
    void getRoles_fallsBackToDb_whenRedisReadFails() {
        given(redisTemplate.opsForValue()).willThrow(new RuntimeException("redis down"));
        given(userRoleRepository.findRoleCodesByUserId(1L)).willReturn(List.of("USER"));

        List<String> roles = roleAuthorityService.getRoles(1L);

        assertThat(roles).containsExactly("USER");
    }

    @Test
    @DisplayName("DB 조회 중 권한이 바뀌면 오래된 역할을 반환하거나 재캐시하지 않는다")
    void getRoles_discardsOldRoles_whenEpochChangesDuringDbRead() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("auth:roles:1")).willReturn(null);
        given(valueOperations.get("auth:roles:epoch:1")).willReturn("0");
        given(userRoleRepository.findRoleCodesByUserId(1L)).willAnswer(ignored -> {
            roleAuthorityService.invalidate(1L);
            return List.of("ADMIN");
        });
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
            .willReturn(0L);

        assertThat(roleAuthorityService.getRoles(1L)).isEmpty();
    }

    @Test
    @DisplayName("관리자 요청은 Redis 역할 캐시를 읽지 않고 primary 조회 결과를 쓴다")
    void getRolesForAdmin_usesPrimaryWithoutRedis() {
        given(primaryRoleQueryService.findCurrentRoles(1L)).willReturn(List.of("USER"));

        assertThat(roleAuthorityService.getRolesForAdmin(1L)).containsExactly("USER");
        then(redisTemplate).shouldHaveNoInteractions();
    }
}
