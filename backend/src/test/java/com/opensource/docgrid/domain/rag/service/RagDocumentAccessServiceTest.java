package com.opensource.docgrid.domain.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.permission.service.query.PrimaryDocumentPermissionQueryService;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/** 출처 불명·손상·권한 조회 오류를 허용으로 해석하지 않는 보안 경계를 검증한다. */
class RagDocumentAccessServiceTest {
    private final PrimaryDocumentPermissionQueryService permissions = mock(PrimaryDocumentPermissionQueryService.class);
    private final RagDocumentAccessService service = new RagDocumentAccessService(permissions, new ObjectMapper());

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "null", "{}", "[0]", "[-1]", "[1.0]", "[\"1\"]", "[null]", "[9223372036854775808]", "{broken"})
    void unknownOrMalformedSourcesAreWithheld(String json) {
        var access = new RagDocumentAccessService.Access(Set.of(1L));
        assertThat(service.permits(access, json)).isFalse();
    }

    @Test
    void transitiveSourcesAreMergedAndEverySourceMustBeReadable() {
        when(permissions.findReadableDocumentIds(eq(7L), any())).thenReturn(Set.of(1L, 2L));
        var access = service.authorize(7L, List.of(1L, 2L, 1L), Arrays.asList("[2,3]", null));
        verify(permissions).findReadableDocumentIds(7L, Set.of(1L, 2L, 3L));
        assertThat(service.permits(access, "[1,2]")).isTrue();
        assertThat(service.permits(access, "[1,2,3]")).isFalse();
        assertThat(service.encode(List.of(3L, 1L, 3L))).isEqualTo("[1,3]");
        assertThat(service.permits(access, "[]")).isTrue();
    }

    @Test
    void primaryPermissionFailureDoesNotBecomeAnEmptySuccessfulDecision() {
        when(permissions.findReadableDocumentIds(eq(7L), any()))
            .thenThrow(new DocGridException(ErrorCode.DOCUMENT_PERMISSION_UNAVAILABLE));
        assertThatThrownBy(() -> service.authorize(7L, List.of(1L)))
            .isInstanceOf(DocGridException.class);
    }
}
