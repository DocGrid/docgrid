package com.opensource.docgrid.domain.search.repository;

/**
 * 질문 단어별 문서 빈도 조회 프로젝션.
 *
 * <p>{@code ord}가 0이면 검색 범위 전체 청크 수이고, 1부터는 질문 단어 순서대로 그 단어가 들어 있는 청크 수다.
 */
public interface PatternCountRow {

    Integer getOrd();

    Long getMatchCount();
}
