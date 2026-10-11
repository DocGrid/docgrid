package com.opensource.docgrid.domain.search.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 하이브리드 검색(벡터 + 희귀 단어 가중 키워드 점수 결합)의 사용 여부와 정책 값을 제공한다.
 *
 * <p>{@code search.hybrid} 설정을 바인딩하고 시작 시 각 값의 안전한 범위를 검증한다. 기본은 켜짐이며,
 * {@code enabled}를 false로 두면 기존 벡터 단독 검색({@code search.vector.*})이 그대로 동작하므로 문제가 생겼을 때
 * 설정만으로 즉시 되돌릴 수 있다. 사용자 요청이 아니라 서버 정책으로만 적용된다.
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "search.hybrid")
public class HybridSearchProperties {

    /** 하이브리드 검색 사용 여부. 기본은 켜짐이며 false로 두면 기존 벡터 단독 검색으로 돌아간다. */
    private boolean enabled = true;

    /** 벡터 유사도가 이 값 이상이면 질문 단어가 겹치지 않아도 결과로 인정한다. */
    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private BigDecimal vectorMinSimilarity = new BigDecimal("0.45");

    /** 질문의 (희귀한 단어일수록 비중이 큰) 단어 중 이 비율 이상을 청크가 가지면 벡터 유사도가 낮아도 결과로 인정한다. */
    @NotNull
    @DecimalMin("0.05")
    @DecimalMax("1.0")
    private BigDecimal minCoverage = new BigDecimal("0.50");

    /** 순위 점수 = 벡터 유사도 + 이 값 × 커버리지. 작게 둬서 벡터 순서를 주도로 하고 단어 일치는 보너스로만 쓴다. */
    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private BigDecimal coverageWeight = new BigDecimal("0.10");

    /** 단어 갈래가 Top-K의 몇 배까지 후보를 조회할지. 문서당 상한 적용 후에도 Top-K가 차도록 여유를 둔다. */
    @Min(1)
    @Max(10)
    private int lexicalCandidatePoolMultiplier = 4;
}
