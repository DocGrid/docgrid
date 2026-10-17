package com.opensource.docgrid.domain.search.benchmark;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 권한 필터 검색 누락 시험(#545)의 Recall·백분위·평균 계산.
 *
 * <p>Recall은 Chunk ID 집합 기준으로 {@code |측정 ∩ 정답| / |정답|}이며, 정답이 비어 있으면 정의하지 않고 {@code null}을
 * 돌려준다. 백분위는 오름차순 정렬된 값에서 최근접 순위(nearest-rank)로 고른다. DB나 Spring에 의존하지 않는다.
 */
final class PermissionRecallMetrics {

    private PermissionRecallMetrics() {
    }

    static Double recall(List<Long> actual, List<Long> expected) {
        if (expected.isEmpty()) {
            return null;
        }
        Set<Long> expectedIds = new HashSet<>(expected);
        long hits = actual.stream().filter(expectedIds::contains).distinct().count();
        return (double) hits / expectedIds.size();
    }

    static double percentile(double[] sortedValues, double quantile) {
        if (sortedValues.length == 0) {
            return Double.NaN;
        }
        int rank = (int) Math.ceil(quantile * sortedValues.length);
        return sortedValues[Math.max(0, Math.min(sortedValues.length - 1, rank - 1))];
    }

    static Double mean(List<Double> values) {
        return values.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).average()
            .stream().boxed().findFirst().orElse(null);
    }

    static Double min(List<Double> values) {
        return values.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).min()
            .stream().boxed().findFirst().orElse(null);
    }
}
