package com.opensource.docgrid.domain.auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 열린 STOMP 세션 재검증 스케줄러를 다른 도메인의 Worker·Dashboard 설정과 독립적으로 활성화한다.
 *
 * <p>{@code @EnableScheduling}을 여러 설정에서 선언해도 Spring은 하나의 scheduling infrastructure로
 * 처리한다. 인증 수명 검증이 다른 기능의 활성화 여부에 따라 조용히 멈추지 않도록 별도 경계를 둔다.
 */
@Configuration
@EnableScheduling
public class StompSessionSchedulingConfig {
}
