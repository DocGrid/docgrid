package com.opensource.docgrid.domain.document.storage;

import java.io.InputStream;
import java.time.Instant;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.document.config.FileStorageProperties;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * MinIO SDK를 사용해 문서 원본을 저장·조회·목록·삭제하는 파일 저장소 Adapter다.
 * MinIO가 선택된 환경에서만 등록되며 공통 Bucket과 Object Key를 저장 위치로 반환하고 검증한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "storage", name = "type", havingValue = "minio")
public class MinioStorageService implements FileStorageService {

    private final MinioClient minioClient;
    private final FileStorageProperties fileStorageProperties;

    /**
     * 설정 Bucket을 준비하고 입력 Stream을 지정 Object Key로 저장한다.
     */
    @Override
    public StoredFile store(InputStream inputStream, long fileSize, String contentType, String objectKey) {
        try {
            // 1. 최초 로컬 환경에서도 업로드할 수 있도록 Bucket 존재를 멱등 보장한다.
            ensureBucketExists();

            // 2. 알려진 크기의 Stream과 Content-Type을 MinIO Object로 저장한다.
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(fileStorageProperties.getBucket())
                    .object(objectKey)
                    .stream(inputStream, fileSize, -1)
                    .contentType(contentType)
                    .build()
            );

            // 3. DB에는 Client 내부 주소가 아니라 Provider·Bucket·Object Key의 논리 위치만 반환한다.
            return new StoredFile(StorageProvider.MINIO, fileStorageProperties.getBucket(), objectKey);
        } catch (Exception e) {
            log.error("MinIO 파일 저장에 실패했습니다.", e);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, e);
        }
    }

    /**
     * 현재 MinIO 설정에 속한 Object를 모두 읽고 SDK Stream을 메서드 안에서 닫는다.
     */
    @Override
    public byte[] read(StoredFile storedFile) {
        // 1. 다른 환경이나 Provider의 Object를 현재 Client로 읽지 않도록 위치를 확인한다.
        validateLocation(storedFile);

        // 2. Object Stream을 Byte 배열로 소유권 이전한 뒤 즉시 닫는다.
        try (InputStream inputStream = minioClient.getObject(
            GetObjectArgs.builder()
                .bucket(storedFile.bucketName())
                .object(storedFile.objectKey())
                .build()
        )) {
            return inputStream.readAllBytes();
        } catch (ErrorResponseException exception) {
            // 3. Object 없음은 영구 파일 누락, 나머지 SDK 오류는 저장소 장애로 구분한다.
            if (isObjectNotFound(exception)) {
                throw new DocGridException(ErrorCode.FILE_OBJECT_NOT_FOUND, exception);
            }
            logStorageReadFailure(exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        } catch (Exception exception) {
            logStorageReadFailure(exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    /** 설정 Bucket에서 접두사가 일치하는 Object를 SDK의 지연 Iterator로 조회한다. */
    @Override
    public Stream<StorageObjectMetadata> streamObjects(String prefix, int pageSize) {
        if (prefix == null || prefix.isBlank() || pageSize <= 0) {
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED);
        }
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                ListObjectsArgs.builder()
                    .bucket(fileStorageProperties.getBucket())
                    .prefix(prefix)
                    .recursive(true)
                    .maxKeys(pageSize)
                    .build()
            );
            return StreamSupport.stream(results.spliterator(), false)
                .map(this::toStorageObjectMetadata);
        } catch (Exception exception) {
            log.error("MinIO Object 목록 조회에 실패했습니다.", exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    /**
     * 현재 MinIO 설정에 속한 Object를 삭제한다.
     */
    @Override
    public void delete(StoredFile storedFile) {
        validateLocation(storedFile);
        try {
            minioClient.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(storedFile.bucketName())
                    .object(storedFile.objectKey())
                    .build()
            );
        } catch (Exception e) {
            log.error("MinIO 파일 삭제에 실패했습니다.", e);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, e);
        }
    }

    /**
     * 설정 Bucket이 없으면 생성하고 동시 생성 경쟁은 재조회로 멱등 처리한다.
     */
    private void ensureBucketExists() throws Exception {
        BucketExistsArgs existsArgs = BucketExistsArgs.builder()
            .bucket(fileStorageProperties.getBucket())
            .build();

        if (minioClient.bucketExists(existsArgs)) {
            return;
        }

        try {
            minioClient.makeBucket(
                MakeBucketArgs.builder()
                    .bucket(fileStorageProperties.getBucket())
                    .build()
            );
        } catch (Exception e) {
            if (!minioClient.bucketExists(existsArgs)) {
                throw e;
            }
        }
    }

    /**
     * MinIO 호환 서버가 반환하는 대표 Object 없음 오류 코드를 판정한다.
     */
    private boolean isObjectNotFound(ErrorResponseException exception) {
        String errorCode = exception.errorResponse().code();
        return "NoSuchKey".equals(errorCode) || "NoSuchObject".equals(errorCode);
    }

    /**
     * 저장 위치가 현재 MINIO Provider와 설정 Bucket에 속하는지 검증한다.
     */
    private void validateLocation(StoredFile storedFile) {
        if (storedFile.storageProvider() == StorageProvider.MINIO
            && fileStorageProperties.getBucket().equals(storedFile.bucketName())) {
            return;
        }
        log.error("현재 MinIO 저장소 설정과 파일 위치가 일치하지 않습니다.");
        throw new DocGridException(ErrorCode.FILE_STORAGE_CONFIGURATION_MISMATCH);
    }

    /**
     * 내부 Bucket·Object Key를 노출하지 않고 저장소 읽기 장애 원인만 기록한다.
     */
    private void logStorageReadFailure(Exception exception) {
        // 원본 저장 위치는 내부 식별 정보이므로 장애 로그에는 예외 원인만 남긴다.
        log.error("MinIO 파일 읽기에 실패했습니다.", exception);
    }

    /** MinIO Result의 지연 오류를 공통 저장소 오류로 변환하면서 Metadata를 만든다. */
    private StorageObjectMetadata toStorageObjectMetadata(Result<Item> result) {
        try {
            Item item = result.get();
            Instant lastModified = item.lastModified() == null ? null : item.lastModified().toInstant();
            return new StorageObjectMetadata(
                new StoredFile(
                    StorageProvider.MINIO,
                    fileStorageProperties.getBucket(),
                    item.objectName()
                ),
                item.size(),
                lastModified
            );
        } catch (Exception exception) {
            log.error("MinIO Object Metadata 조회에 실패했습니다.", exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }
}
