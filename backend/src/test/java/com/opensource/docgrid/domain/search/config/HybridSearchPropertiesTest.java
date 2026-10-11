package com.opensource.docgrid.domain.search.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

/**
 * 하이브리드 검색 설정의 기본값과 허용 범위를 검증한다. 기본은 켜짐이고, false로 꺼서 기존 벡터 단독 검색으로 되돌릴 수 있어야 한다.
 */
@DisplayName("HybridSearchProperties 검증 테스트")
class HybridSearchPropertiesTest {

    @Test
    @DisplayName("기본값은 켜짐이고 임계값과 가중치는 설계 값이다")
    void defaults_areEnabledWithDesignValues() {
        HybridSearchProperties properties = new HybridSearchProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getVectorMinSimilarity()).isEqualByComparingTo("0.45");
        assertThat(properties.getMinCoverage()).isEqualByComparingTo("0.50");
        assertThat(properties.getCoverageWeight()).isEqualByComparingTo("0.10");
        assertThat(properties.getLexicalCandidatePoolMultiplier()).isEqualTo(4);
        assertThat(violations(properties)).isEmpty();
    }

    @Test
    @DisplayName("정상 케이스: false로 설정하면 하이브리드를 끌 수 있다")
    void enabled_canBeTurnedOff() {
        HybridSearchProperties properties = new HybridSearchProperties();
        properties.setEnabled(false);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(violations(properties)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0", "0.45", "1.0"})
    @DisplayName("정상 케이스: 0부터 1까지의 벡터 최소 유사도를 허용한다")
    void vectorMinSimilarity_inRange_hasNoViolations(String value) {
        HybridSearchProperties properties = new HybridSearchProperties();
        properties.setVectorMinSimilarity(new BigDecimal(value));

        assertThat(violations(properties)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "1.01"})
    @DisplayName("예외 케이스: 0부터 1 범위를 벗어난 벡터 최소 유사도를 거부한다")
    void vectorMinSimilarity_outOfRange_hasViolation(String value) {
        HybridSearchProperties properties = new HybridSearchProperties();
        properties.setVectorMinSimilarity(new BigDecimal(value));

        assertThat(violations(properties)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0", "0.04", "1.01"})
    @DisplayName("예외 케이스: 너무 낮거나 범위를 벗어난 최소 커버리지를 거부한다")
    void minCoverage_invalid_hasViolation(String value) {
        HybridSearchProperties properties = new HybridSearchProperties();
        properties.setMinCoverage(new BigDecimal(value));

        assertThat(violations(properties)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.1", "1.01"})
    @DisplayName("예외 케이스: 0부터 1 범위를 벗어난 커버리지 가중치를 거부한다")
    void coverageWeight_outOfRange_hasViolation(String value) {
        HybridSearchProperties properties = new HybridSearchProperties();
        properties.setCoverageWeight(new BigDecimal(value));

        assertThat(violations(properties)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 11})
    @DisplayName("예외 케이스: 허용 범위를 벗어난 단어 후보 풀 배수를 거부한다")
    void lexicalCandidatePoolMultiplier_outOfRange_hasViolation(int value) {
        HybridSearchProperties properties = new HybridSearchProperties();
        properties.setLexicalCandidatePoolMultiplier(value);

        assertThat(violations(properties)).isNotEmpty();
    }

    private Set<?> violations(HybridSearchProperties properties) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(properties);
        }
    }
}
