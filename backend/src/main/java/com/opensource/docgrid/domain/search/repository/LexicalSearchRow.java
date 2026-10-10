package com.opensource.docgrid.domain.search.repository;

/**
 * 희귀 단어 가중 검색 네이티브 쿼리 프로젝션.
 *
 * <p>벡터 검색 결과({@link VectorSearchRow})에 질문 단어 커버리지({@code coverage})를 더한다. 단어로만 잡힌 청크도
 * 응답·저장에 쓸 벡터 거리({@code distance})를 항상 함께 가지므로, 호출 측은 두 갈래의 후보를 같은
 * {@code VectorSearchCandidate}로 변환할 수 있다.
 */
public interface LexicalSearchRow extends VectorSearchRow {

    /**
     * 질문 단어 중 이 청크가 가진 단어의 가중치 합 / 전체 가중치 합(0~1). 희귀한 단어를 가질수록 크다.
     */
    Double getCoverage();
}
