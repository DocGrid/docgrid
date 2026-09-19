package com.opensource.docgrid.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.opensource.docgrid.domain.document.config.FileStorageProperties;
import com.opensource.docgrid.domain.document.config.FileStorageType;
import com.opensource.docgrid.domain.document.config.StorageOrphanGcProperties;
import com.opensource.docgrid.domain.document.dto.StorageOrphanInspectionResult;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.repository.FileObjectRepository;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StorageObjectMetadata;
import com.opensource.docgrid.domain.document.storage.StoredFile;

/**
 * 고아 Object 탐지가 안전 필터와 DB Bulk 비교를 적용하고 저장소를 변경하지 않는지 검증한다.
 */
@DisplayName("StorageOrphanInspectionService 테스트")
class StorageOrphanInspectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-19T00:00:00Z");
    private static final String BUCKET = "docgrid";
    private static final String REFERENCED_KEY = key("11111111", "aaaaaaaa", "pdf");
    private static final String ORPHAN_KEY = key("22222222", "bbbbbbbb", "txt");
    private static final String SECOND_ORPHAN_KEY = key("33333333", "cccccccc", "md");

    private FileStorageService fileStorageService;
    private FileObjectRepository fileObjectRepository;
    private StorageOrphanInspectionService inspectionService;

    @BeforeEach
    void setUp() {
        fileStorageService = mock(FileStorageService.class);
        fileObjectRepository = mock(FileObjectRepository.class);

        FileStorageProperties storageProperties = new FileStorageProperties();
        storageProperties.setType(FileStorageType.LOCAL);
        storageProperties.setBucket(BUCKET);

        StorageOrphanGcProperties gcProperties = new StorageOrphanGcProperties();
        gcProperties.setGracePeriod(Duration.ofHours(72));
        gcProperties.setPageSize(2);

        inspectionService = new StorageOrphanInspectionService(
            fileStorageService,
            fileObjectRepository,
            storageProperties,
            gcProperties,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    @DisplayName("오래된 유효 Object만 Batch 비교하고 미참조 후보와 용량을 집계한다")
    void inspect_filtersAndComparesCandidatesInBatches() {
        String recentKey = key("44444444", "dddddddd", "docx");
        StorageObjectMetadata invalidExtension = metadata(
            key("55555555", "eeeeeeee", "exe"), 13L, NOW.minus(Duration.ofDays(10))
        );
        StorageObjectMetadata missingTimestamp = new StorageObjectMetadata(
            new StoredFile(StorageProvider.LOCAL, BUCKET, key("66666666", "ffffffff", "pdf")),
            17L,
            null
        );
        given(fileStorageService.streamObjects(StorageOrphanInspectionService.DOCUMENT_PREFIX, 2))
            .willReturn(Stream.of(
                metadata(REFERENCED_KEY, 10L, NOW.minus(Duration.ofDays(5))),
                metadata(ORPHAN_KEY, 20L, NOW.minus(Duration.ofDays(4))),
                metadata(SECOND_ORPHAN_KEY, 30L, NOW.minus(Duration.ofDays(6))),
                metadata(recentKey, 40L, NOW.minus(Duration.ofHours(1))),
                invalidExtension,
                missingTimestamp
            ));
        given(fileObjectRepository.findReferencedObjectKeys(
            eq(StorageProvider.LOCAL),
            eq(BUCKET),
            anyCollection()
        )).willAnswer(invocation -> {
            Collection<String> keys = invocation.getArgument(2);
            return keys.contains(REFERENCED_KEY) ? List.of(REFERENCED_KEY) : List.of();
        });

        StorageOrphanInspectionResult result = inspectionService.inspect();

        assertThat(result).isEqualTo(new StorageOrphanInspectionResult(6L, 1L, 2L, 50L, 1L, 2L));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> keysCaptor = ArgumentCaptor.forClass(Collection.class);
        then(fileObjectRepository).should(org.mockito.Mockito.times(2)).findReferencedObjectKeys(
            eq(StorageProvider.LOCAL),
            eq(BUCKET),
            keysCaptor.capture()
        );
        assertThat(keysCaptor.getAllValues()).containsExactly(
            List.of(REFERENCED_KEY, ORPHAN_KEY),
            List.of(SECOND_ORPHAN_KEY)
        );
        then(fileStorageService).should(never()).delete(any());
    }

    @Test
    @DisplayName("DB 비교가 실패하면 Object Stream을 닫고 부분 결과를 반환하지 않는다")
    void inspect_closesStreamAndPropagatesFailure() {
        AtomicBoolean closed = new AtomicBoolean();
        Stream<StorageObjectMetadata> objects = Stream.of(
            metadata(ORPHAN_KEY, 20L, NOW.minus(Duration.ofDays(4))),
            metadata(SECOND_ORPHAN_KEY, 30L, NOW.minus(Duration.ofDays(6)))
        ).onClose(() -> closed.set(true));
        given(fileStorageService.streamObjects(StorageOrphanInspectionService.DOCUMENT_PREFIX, 2))
            .willReturn(objects);
        given(fileObjectRepository.findReferencedObjectKeys(any(), any(), anyCollection()))
            .willThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(inspectionService::inspect)
            .isInstanceOf(IllegalStateException.class);

        assertThat(closed).isTrue();
        then(fileStorageService).should(never()).delete(any());
    }

    private StorageObjectMetadata metadata(String objectKey, long size, Instant lastModified) {
        return new StorageObjectMetadata(
            new StoredFile(StorageProvider.LOCAL, BUCKET, objectKey),
            size,
            lastModified
        );
    }

    private static String key(String directoryPrefix, String filePrefix, String extension) {
        return "documents/" + directoryPrefix + "-1111-4111-8111-111111111111/"
            + filePrefix + "-2222-4222-8222-222222222222." + extension;
    }
}
