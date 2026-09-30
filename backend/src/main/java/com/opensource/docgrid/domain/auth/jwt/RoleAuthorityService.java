package com.opensource.docgrid.domain.auth.jwt;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.auth.service.query.PrimaryRoleQueryService;
import com.opensource.docgrid.domain.user.repository.UserRoleRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 인가(hasRole) 판단에 쓰는 사용자 role을 JWT에 고정하지 않고 DB를 기준으로 관리한다.
 *
 * <p>JWT에 role을 박제하면 관리자가 role을 부여/회수해도 재로그인 전까지 반영되지 않는다.
 * 일반 요청의 DB 조회 부하를 줄이기 위해 Redis에 짧은 TTL로 캐싱한다. HTTP 관리자
 * 인가는 캐시를 우회해 primary를 매번 검증하며, role 변경 시 캐시 세대를 올린다.
 *
 * <p>일반 요청은 Redis 장애 시 기존과 같이 DB 조회로 폴백한다. 관리자 HTTP 요청은
 * 가용성보다 최신 권한을 우선하며 primary 검증 실패를 호출자에게 전파한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoleAuthorityService {

    private static final String KEY_PREFIX = "auth:roles:";
    private static final String EPOCH_PREFIX = "auth:roles:epoch:";
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final RedisScript<Long> INVALIDATE = new DefaultRedisScript<>("""
        redis.call('INCR', KEYS[2])
        redis.call('DEL', KEYS[1])
        return 1
        """, Long.class);
    private static final RedisScript<Long> CACHE_IF_UNCHANGED = new DefaultRedisScript<>("""
        local current = redis.call('GET', KEYS[2]) or '0'
        if current ~= ARGV[1] then return 0 end
        redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
        return 1
        """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final UserRoleRepository userRoleRepository;
    private final PrimaryRoleQueryService primaryRoleQueryService;

    public List<String> getRoles(Long userId) {
        // 1. 먼저 Redis 캐시를 확인한다 — 대부분의 요청은 여기서 끝나 DB 부하를 줄인다.
        String cached = readCache(userId);
        if (cached != null) {
            return cached.isBlank() ? List.of() : Arrays.asList(cached.split(","));
        }

        // 2. 조회 전 세대를 기억해 회수·부여가 DB 조회와 캐시 저장 사이에 끼어드는지 확인한다.
        String epoch = readEpoch(userId);
        List<String> roles = userRoleRepository.findRoleCodesByUserId(userId);

        // 3. 세대가 바뀌면 조회 결과를 반환하거나 재캐시하지 않는다.
        if (epoch != null && !writeCacheIfUnchanged(userId, epoch, roles)) {
            return List.of();
        }
        return roles;
    }

    public List<String> getRolesForAdmin(Long userId) {
        // 관리자 인가는 Redis 상태와 복제 지연에 관계없이 현재 primary만 신뢰한다.
        return primaryRoleQueryService.findCurrentRoles(userId);
    }

    public void invalidate(Long userId) {
        try {
            // 세대 증가와 삭제가 원자적이어야 이전 DB 읽기가 삭제 뒤 캐시를 부활시키지 못한다.
            Long invalidated = redisTemplate.execute(INVALIDATE,
                List.of(KEY_PREFIX + userId, EPOCH_PREFIX + userId));
            if (!Long.valueOf(1).equals(invalidated)) {
                log.error("Redis role 캐시 무효화 결과를 확인할 수 없습니다. userId={}", userId);
            }
        } catch (Exception e) {
            log.error("Redis role 캐시 무효화 실패, userId={}: {}", userId, e.getMessage());
        }
    }

    private String readCache(Long userId) {
        try {
            return redisTemplate.opsForValue().get(KEY_PREFIX + userId);
        } catch (Exception e) {
            log.error("Redis role 캐시 조회 실패, DB로 폴백합니다. userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    private String readEpoch(Long userId) {
        try {
            String epoch = redisTemplate.opsForValue().get(EPOCH_PREFIX + userId);
            return epoch == null ? "0" : epoch;
        } catch (Exception e) {
            log.error("Redis role 캐시 세대 조회 실패, DB로 폴백합니다. userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    private boolean writeCacheIfUnchanged(Long userId, String epoch, List<String> roles) {
        try {
            Long saved = redisTemplate.execute(CACHE_IF_UNCHANGED,
                List.of(KEY_PREFIX + userId, EPOCH_PREFIX + userId), epoch,
                String.join(",", roles), String.valueOf(TTL.toSeconds()));
            return Long.valueOf(1).equals(saved);
        } catch (Exception e) {
            log.error("Redis role 캐시 저장 실패, DB 조회 결과를 사용합니다. userId={}: {}", userId, e.getMessage());
            return true;
        }
    }
}
