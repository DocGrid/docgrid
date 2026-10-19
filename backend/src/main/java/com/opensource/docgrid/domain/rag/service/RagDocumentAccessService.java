package com.opensource.docgrid.domain.rag.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.permission.service.query.PrimaryDocumentPermissionQueryService;

import lombok.RequiredArgsConstructor;

/**
 * 저장 답변·발췌·모델 문맥의 출처를 현재 Primary 권한으로 판정한다.
 * 출처가 없거나 손상된 답변은 허용으로 추정하지 않으며 호출 단위 밖에 권한을 캐시하지 않는다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class RagDocumentAccessService {
    public static final String WITHHELD_ANSWER = "현재 문서 권한으로 이전 답변을 표시할 수 없습니다. 다시 검색해 주세요.";

    private final PrimaryDocumentPermissionQueryService permissions;
    private final ObjectMapper objectMapper;

    /** 고유 문서 목록을 한 번만 인가해 같은 응답 내 중복 권한 조회를 피한다. */
    public Access authorize(Long userId, Collection<Long> documentIds) {
        Set<Long> unique = new LinkedHashSet<>(documentIds);
        return new Access(permissions.findReadableDocumentIds(userId, unique));
    }

    /** 발췌와 답변의 전이적 출처를 합쳐 같은 조회 안에서 한 번씩만 검사한다. */
    public Access authorize(Long userId, Collection<Long> documentIds, Collection<String> sourceLists) {
        Set<Long> all = new LinkedHashSet<>(documentIds);
        for (String json : sourceLists) {
            var ids = sources(json);
            if (ids != null) all.addAll(ids);
        }
        return authorize(userId, all);
    }

    /** 출처 전체가 현재 읽기 가능한 경우에만 원래 답변·문맥을 허용한다. */
    public boolean permits(Access access, String json) { return access.permits(sources(json)); }

    /** 전체 출처를 엄격히 해석한다. NULL·잘못된 값은 추적 불명이므로 null로 반환한다. */
    public Set<Long> sources(String json) {
        if (json == null) return null;
        try {
            var array = objectMapper.readTree(json);
            if (array == null || !array.isArray()) return null;
            Set<Long> ids = new LinkedHashSet<>();
            for (var value : array) {
                if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) return null;
                ids.add(value.longValue());
            }
            return Set.copyOf(ids);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    /** 새 모델 입력의 직접·전이적 출처를 결정적인 JSON으로 저장한다. */
    public String encode(Collection<Long> documentIds) {
        try {
            return objectMapper.writeValueAsString(documentIds.stream().distinct().sorted().toList());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("RAG source serialization failed", exception);
        }
    }

    /** 하나의 Primary 판정 결과를 공유하는 불변 권한 Snapshot이다. */
    public record Access(Set<Long> readableDocumentIds) {
        public Access { readableDocumentIds = Set.copyOf(readableDocumentIds); }
        public boolean canRead(Long documentId) { return readableDocumentIds.contains(documentId); }
        public boolean permits(Set<Long> sources) {
            return sources != null && readableDocumentIds.containsAll(sources);
        }
    }
}
