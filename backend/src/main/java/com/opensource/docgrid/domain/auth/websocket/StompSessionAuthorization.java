package com.opensource.docgrid.domain.auth.websocket;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * STOMP CONNECT가 성공한 시점의 token 식별자·만료 시각·역할 snapshot을 세션 수명 검증에 전달한다.
 *
 * <p>JWT 원문과 이메일은 보관하지 않는다. 역할은 순서와 중복에 영향을 받지 않도록 불변 Set으로
 * 정규화하며, 이후 검사에서 Redis blacklist와 DB의 현재 역할을 이 snapshot과 비교한다.
 */
public record StompSessionAuthorization(
    Long userId,
    String jti,
    Instant expiresAt,
    Set<String> roles
) {

    public StompSessionAuthorization {
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        Objects.requireNonNull(jti, "jti는 필수입니다.");
        Objects.requireNonNull(expiresAt, "expiresAt은 필수입니다.");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles는 필수입니다."));
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
