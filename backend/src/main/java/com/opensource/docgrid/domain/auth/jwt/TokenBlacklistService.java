package com.opensource.docgrid.domain.auth.jwt;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final String KEY_PREFIX = "auth:blacklist:";

    private final StringRedisTemplate redisTemplate;

    public void blacklist(String jti, long ttlSeconds) {
        redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofSeconds(ttlSeconds));
    }

    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
    }

    /**
     * 여러 STOMP 세션의 token 폐기 상태를 Redis MGET 한 번으로 확인한다.
     *
     * <p>응답 누락을 정상 token으로 오인하면 기존 연결이 계속 살아남으므로, Redis가 요청 key와 같은
     * 개수의 결과를 주지 않으면 검증 실패로 처리한다. 호출자는 WebSocket fail-closed 정책에 따라
     * 검사 대상 세션을 종료한다.
     */
    public Set<String> findBlacklistedJtis(Collection<String> jtis) {
        List<String> distinctJtis = jtis.stream().distinct().toList();
        if (distinctJtis.isEmpty()) {
            return Set.of();
        }

        List<String> keys = distinctJtis.stream()
            .map(jti -> KEY_PREFIX + jti)
            .toList();
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        if (values == null || values.size() != keys.size()) {
            throw new IllegalStateException("Redis blacklist 일괄 조회 결과가 완전하지 않습니다.");
        }

        Set<String> blacklisted = new HashSet<>();
        for (int index = 0; index < distinctJtis.size(); index++) {
            if (values.get(index) != null) {
                blacklisted.add(distinctJtis.get(index));
            }
        }
        return Set.copyOf(blacklisted);
    }
}
