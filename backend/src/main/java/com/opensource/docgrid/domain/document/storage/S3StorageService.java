package com.opensource.docgrid.domain.document.storage;

import java.io.InputStream;
import java.time.Instant;
import java.util.stream.Stream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.opensource.docgrid.domain.document.config.FileStorageProperties;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * AWS SDK v2를 사용해 문서 원본을 저장·조회·목록·삭제하는 파일 저장소 Adapter다.
 * Bucket 생성과 권한 관리는 인프라 경계에 두고 설정과 일치하는 Object 작업만 수행한다.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "storage", name = "type", havingValue = "s3")
public class S3StorageService implements FileStorageService {

    private final S3Client s3Client;
    private final String bucketName;

    /**
     * S3 Client와 비어 있지 않은 애플리케이션 Bucket을 연결한다.
     */
    public S3StorageService(S3Client s3Client, FileStorageProperties fileStorageProperties) {
        this.s3Client = s3Client;
        this.bucketName = requireBucket(fileStorageProperties.getBucket());
    }

    /**
     * 입력 Stream을 알려진 크기와 Content-Type으로 설정 Bucket의 Object에 저장한다.
     */
    @Override
    public StoredFile store(InputStream inputStream, long fileSize, String contentType, String objectKey) {
        try {
            // 1. 설정 Bucket과 논리 Object Key로 SDK 요청을 조립한다.
            PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .contentType(contentType)
                .build();

            // 2. 알려진 파일 크기로 Stream을 전송하고 DB에 보존할 논리 위치를 반환한다.
            s3Client.putObject(request, RequestBody.fromInputStream(inputStream, fileSize));
            return new StoredFile(StorageProvider.S3, bucketName, objectKey);
        } catch (Exception exception) {
            log.error("S3 파일 저장에 실패했습니다.", exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    /**
     * 현재 S3 설정에 속한 Object 전체를 Byte 배열로 읽는다.
     */
    @Override
    public byte[] read(StoredFile storedFile) {
        // 1. 다른 Provider 또는 Bucket의 Object를 현재 Client로 읽지 못하도록 위치를 검증한다.
        validateLocation(storedFile);

        // 2. Object를 읽고 명시적·일반 404는 파일 누락, 그 밖의 오류는 저장소 장애로 변환한다.
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                .bucket(storedFile.bucketName())
                .key(storedFile.objectKey())
                .build();
            return s3Client.getObjectAsBytes(request).asByteArray();
        } catch (NoSuchKeyException exception) {
            throw new DocGridException(ErrorCode.FILE_OBJECT_NOT_FOUND, exception);
        } catch (S3Exception exception) {
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

    /** 설정 Bucket에서 접두사가 일치하는 Object를 SDK Paginator로 지연 조회한다. */
    @Override
    public Stream<StorageObjectMetadata> streamObjects(String prefix, int pageSize) {
        if (!StringUtils.hasText(prefix) || pageSize <= 0 || pageSize > 1_000) {
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED);
        }
        try {
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .maxKeys(pageSize)
                .build();
            return s3Client.listObjectsV2Paginator(request).contents().stream()
                .map(item -> new StorageObjectMetadata(
                    new StoredFile(StorageProvider.S3, bucketName, item.key()),
                    item.size(),
                    item.lastModified() == null ? null : Instant.from(item.lastModified())
                ));
        } catch (Exception exception) {
            log.error("S3 Object 목록 조회에 실패했습니다.", exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    /**
     * 현재 S3 설정에 속한 Object를 삭제한다.
     */
    @Override
    public void delete(StoredFile storedFile) {
        validateLocation(storedFile);
        try {
            s3Client.deleteObject(
                DeleteObjectRequest.builder()
                    .bucket(storedFile.bucketName())
                    .key(storedFile.objectKey())
                    .build()
            );
        } catch (Exception exception) {
            log.error("S3 파일 삭제에 실패했습니다.", exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    /**
     * HTTP 상태와 AWS 오류 코드를 함께 사용해 S3 호환 서비스의 Object 없음 응답을 판정한다.
     */
    private boolean isObjectNotFound(S3Exception exception) {
        if (exception.statusCode() == 404) {
            return true;
        }
        if (exception.awsErrorDetails() == null) {
            return false;
        }
        String errorCode = exception.awsErrorDetails().errorCode();
        return "NoSuchKey".equals(errorCode) || "NoSuchObject".equals(errorCode);
    }

    /**
     * 인프라에서 미리 준비해야 하는 S3 Bucket 설정이 비어 있지 않은지 확인한다.
     */
    private String requireBucket(String configuredBucket) {
        if (!StringUtils.hasText(configuredBucket)) {
            throw new IllegalStateException("S3 Adapter에는 STORAGE_BUCKET 설정이 필요합니다.");
        }
        return configuredBucket;
    }

    /**
     * 저장 위치가 현재 S3 Provider와 설정 Bucket에 속하는지 검증한다.
     */
    private void validateLocation(StoredFile storedFile) {
        if (storedFile.storageProvider() == StorageProvider.S3
            && bucketName.equals(storedFile.bucketName())) {
            return;
        }
        log.error("현재 S3 저장소 설정과 파일 위치가 일치하지 않습니다.");
        throw new DocGridException(ErrorCode.FILE_STORAGE_CONFIGURATION_MISMATCH);
    }

    /**
     * 내부 Bucket·Object Key를 노출하지 않고 저장소 읽기 장애 원인만 기록한다.
     */
    private void logStorageReadFailure(Exception exception) {
        // Bucket과 Object Key는 내부 식별 정보이므로 장애 로그에는 예외 원인만 남긴다.
        log.error("S3 파일 읽기에 실패했습니다.", exception);
    }
}
