package com.opensource.docgrid.domain.embedding.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;

/**
 * EmbeddingJobRecoveryQueryService의 후보 Snapshot 위임과 내부 입력 경계를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmbeddingJobRecoveryQueryService 테스트")
class EmbeddingJobRecoveryQueryServiceTest {

    private static final LocalDateTime RECOVERED_AT = LocalDateTime.of(2026, 8, 3, 15, 0);

    @Mock private EmbeddingJobRepository embeddingJobRepository;

    @Test
    @DisplayName("복구 기준 시각과 Batch 크기로 만료 Job ID Snapshot을 조회한다")
    void findExpiredJobIds_returnsRepositorySnapshot() {
        EmbeddingJobRecoveryQueryService queryService =
            new EmbeddingJobRecoveryQueryService(embeddingJobRepository);
        given(embeddingJobRepository.findExpiredProcessingJobIds(RECOVERED_AT, 100))
            .willReturn(List.of(10L, 11L));

        assertThat(queryService.findExpiredJobIds(RECOVERED_AT, 100)).containsExactly(10L, 11L);
    }

    @Test
    @DisplayName("문서 버전 범위를 지정하면 해당 버전의 만료 Lease만 조회한다")
    void findExpiredJobIdsForDocumentVersion_returnsScopedSnapshot() {
        EmbeddingJobRecoveryQueryService queryService =
            new EmbeddingJobRecoveryQueryService(embeddingJobRepository);
        given(embeddingJobRepository.findExpiredProcessingJobIdsForDocumentVersion(RECOVERED_AT, 100, 42L))
            .willReturn(List.of(10L));

        assertThat(queryService.findExpiredJobIdsForDocumentVersion(RECOVERED_AT, 100, 42L))
            .containsExactly(10L);
        then(embeddingJobRepository).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("복구 기준 시각이 없거나 Batch 크기가 양수가 아니면 조회하지 않는다")
    void findExpiredJobIds_throws_when_inputIsInvalid() {
        EmbeddingJobRecoveryQueryService queryService =
            new EmbeddingJobRecoveryQueryService(embeddingJobRepository);

        assertThatThrownBy(() -> queryService.findExpiredJobIds(null, 100))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queryService.findExpiredJobIds(RECOVERED_AT, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queryService.findExpiredJobIdsForDocumentVersion(RECOVERED_AT, 100, 0L))
            .isInstanceOf(IllegalArgumentException.class);
        then(embeddingJobRepository).shouldHaveNoInteractions();
    }
}
