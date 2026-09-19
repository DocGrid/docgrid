package com.opensource.docgrid.domain.document.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.document.config.FileStorageProperties;
import com.opensource.docgrid.domain.document.config.StorageOrphanGcProperties;
import com.opensource.docgrid.domain.document.dto.StorageOrphanInspectionResult;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.repository.FileObjectRepository;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StorageObjectMetadata;
import com.opensource.docgrid.domain.document.storage.StoredFile;

import lombok.RequiredArgsConstructor;

/**
 * 활성 저장소의 문서 Object와 FileObject 참조를 Batch로 대조해 오래된 미참조 후보를 집계한다.
 * 1단계 안전 경계상 저장소 삭제나 DB 변경은 수행하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class StorageOrphanInspectionService {

    static final String DOCUMENT_PREFIX = "documents/";
    private static final Pattern DOCUMENT_OBJECT_KEY = Pattern.compile(
        "^documents/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/"
            + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
            + "\\.(?:txt|md|pdf|docx)$"
    );

    private final FileStorageService fileStorageService;
    private final FileObjectRepository fileObjectRepository;
    private final FileStorageProperties fileStorageProperties;
    private final StorageOrphanGcProperties orphanGcProperties;
    private final Clock clock;

    /** Object Stream을 끝까지 읽은 경우에만 완성된 탐지 Snapshot을 반환한다. */
    public StorageOrphanInspectionResult inspect() {
        // 1. 전체 실행에서 같은 기준 시각과 활성 저장 위치를 사용해 경계 판정이 흔들리지 않게 한다.
        Instant staleBefore = clock.instant().minus(orphanGcProperties.getGracePeriod());
        StorageProvider activeProvider = StorageProvider.valueOf(fileStorageProperties.getType().name());
        String activeBucket = fileStorageProperties.getBucket();
        InspectionAccumulator accumulator = new InspectionAccumulator();
        List<StorageObjectMetadata> batch = new ArrayList<>(orphanGcProperties.getPageSize());

        // 2. 저장소 목록을 지연 순회하고 형식·위치·유예 시간 검증을 통과한 Object만 비교 Batch에 담는다.
        try (Stream<StorageObjectMetadata> objects = fileStorageService.streamObjects(
            DOCUMENT_PREFIX,
            orphanGcProperties.getPageSize()
        )) {
            objects.forEach(metadata -> {
                accumulator.scannedObjects++;
                if (!isValidCandidateMetadata(metadata, activeProvider, activeBucket)) {
                    accumulator.invalidObjects++;
                    return;
                }
                if (metadata.lastModified().isAfter(staleBefore)) {
                    accumulator.recentObjects++;
                    return;
                }
                batch.add(metadata);
                if (batch.size() == orphanGcProperties.getPageSize()) {
                    compareReferences(batch, activeProvider, activeBucket, accumulator);
                    batch.clear();
                }
            });
        }

        // 3. 마지막 미완성 Batch를 비교한 뒤 전체 실행 결과를 불변 Snapshot으로 만든다.
        compareReferences(batch, activeProvider, activeBucket, accumulator);
        return accumulator.toResult();
    }

    private boolean isValidCandidateMetadata(
        StorageObjectMetadata metadata,
        StorageProvider activeProvider,
        String activeBucket
    ) {
        if (metadata == null || metadata.storedFile() == null
            || metadata.lastModified() == null || metadata.size() < 0) {
            return false;
        }
        StoredFile storedFile = metadata.storedFile();
        return storedFile.storageProvider() == activeProvider
            && activeBucket.equals(storedFile.bucketName())
            && storedFile.objectKey() != null
            && DOCUMENT_OBJECT_KEY.matcher(storedFile.objectKey()).matches();
    }

    private void compareReferences(
        List<StorageObjectMetadata> batch,
        StorageProvider activeProvider,
        String activeBucket,
        InspectionAccumulator accumulator
    ) {
        if (batch.isEmpty()) {
            return;
        }
        List<String> objectKeys = batch.stream()
            .map(metadata -> metadata.storedFile().objectKey())
            .toList();
        Set<String> referencedKeys = new HashSet<>(fileObjectRepository.findReferencedObjectKeys(
            activeProvider,
            activeBucket,
            objectKeys
        ));

        for (StorageObjectMetadata metadata : batch) {
            if (referencedKeys.contains(metadata.storedFile().objectKey())) {
                accumulator.referencedObjects++;
            } else {
                accumulator.orphanCandidates++;
                accumulator.orphanCandidateBytes += metadata.size();
            }
        }
    }

    /** 한 실행의 가변 집계를 Service 내부에 가두고 외부에는 불변 결과만 전달한다. */
    private static final class InspectionAccumulator {

        private long scannedObjects;
        private long referencedObjects;
        private long orphanCandidates;
        private long orphanCandidateBytes;
        private long recentObjects;
        private long invalidObjects;

        private StorageOrphanInspectionResult toResult() {
            return new StorageOrphanInspectionResult(
                scannedObjects,
                referencedObjects,
                orphanCandidates,
                orphanCandidateBytes,
                recentObjects,
                invalidObjects
            );
        }
    }
}
