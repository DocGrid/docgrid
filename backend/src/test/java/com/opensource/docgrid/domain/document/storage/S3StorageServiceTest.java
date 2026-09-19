package com.opensource.docgrid.domain.document.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.opensource.docgrid.domain.document.config.FileStorageProperties;
import com.opensource.docgrid.domain.document.config.FileStorageType;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

/**
 * AWS S3 Adapter의 Object 위치 전달과 SDK 오류 변환 계약을 검증한다.
 * 실제 AWS 자격 증명이나 Network 없이 Port 구현의 경계만 단위 테스트한다.
 */
@DisplayName("S3StorageService 테스트")
class S3StorageServiceTest {

    private static final StoredFile STORED_FILE = new StoredFile(
        StorageProvider.S3, "source-bucket", "documents/source.txt"
    );

    private S3Client s3Client;
    private S3StorageService storageService;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        FileStorageProperties properties = new FileStorageProperties();
        properties.setType(FileStorageType.S3);
        properties.setBucket(STORED_FILE.bucketName());
        storageService = new S3StorageService(s3Client, properties);
    }

    @Test
    @DisplayName("S3 Bucket 설정이 없으면 Adapter 생성 단계에서 거부한다")
    void constructor_rejectsMissingBucket() {
        FileStorageProperties properties = new FileStorageProperties();
        properties.setType(FileStorageType.S3);

        assertThatThrownBy(() -> new S3StorageService(s3Client, properties))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("STORAGE_BUCKET");
    }

    @Test
    @DisplayName("공통 Bucket에 저장하고 S3 Provider 위치를 반환한다")
    void store_returnsS3Location() {
        byte[] content = "DocGrid S3 원본".getBytes(StandardCharsets.UTF_8);
        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        given(s3Client.putObject(requestCaptor.capture(), bodyCaptor.capture()))
            .willReturn(PutObjectResponse.builder().build());

        StoredFile result = storageService.store(
            new ByteArrayInputStream(content),
            content.length,
            "text/plain",
            "documents/new.txt"
        );

        assertThat(result).isEqualTo(
            new StoredFile(StorageProvider.S3, STORED_FILE.bucketName(), "documents/new.txt")
        );
        assertThat(requestCaptor.getValue().bucket()).isEqualTo(STORED_FILE.bucketName());
        assertThat(requestCaptor.getValue().key()).isEqualTo("documents/new.txt");
        assertThat(requestCaptor.getValue().contentType()).isEqualTo("text/plain");
        assertThat(bodyCaptor.getValue().optionalContentLength()).contains((long) content.length);
    }

    @Test
    @DisplayName("전달된 Bucket과 Object Key로 모든 Byte를 읽는다")
    void read_returnsAllBytes() {
        byte[] content = "DocGrid 원본".getBytes(StandardCharsets.UTF_8);
        ArgumentCaptor<GetObjectRequest> requestCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        given(s3Client.getObjectAsBytes(requestCaptor.capture()))
            .willReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), content));

        byte[] result = storageService.read(STORED_FILE);

        assertThat(result).isEqualTo(content);
        assertThat(requestCaptor.getValue().bucket()).isEqualTo(STORED_FILE.bucketName());
        assertThat(requestCaptor.getValue().key()).isEqualTo(STORED_FILE.objectKey());
    }

    @Test
    @DisplayName("접두사와 Page 크기로 S3 Object Metadata를 조회한다")
    void streamObjects_listsMetadataWithConfiguredScope() {
        Instant modifiedAt = Instant.parse("2026-09-18T00:00:00Z");
        S3Object item = S3Object.builder()
            .key("documents/a/source.pdf")
            .size(37L)
            .lastModified(modifiedAt)
            .build();
        ArgumentCaptor<ListObjectsV2Request> requestCaptor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        given(s3Client.listObjectsV2Paginator(requestCaptor.capture()))
            .willAnswer(invocation -> new ListObjectsV2Iterable(s3Client, invocation.getArgument(0)));
        given(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
            .willReturn(ListObjectsV2Response.builder().contents(item).isTruncated(false).build());

        List<StorageObjectMetadata> result;
        try (var objects = storageService.streamObjects("documents/", 200)) {
            result = objects.toList();
        }

        assertThat(result).containsExactly(new StorageObjectMetadata(
            new StoredFile(StorageProvider.S3, STORED_FILE.bucketName(), "documents/a/source.pdf"),
            37L,
            modifiedAt
        ));
        assertThat(requestCaptor.getValue().bucket()).isEqualTo(STORED_FILE.bucketName());
        assertThat(requestCaptor.getValue().prefix()).isEqualTo("documents/");
        assertThat(requestCaptor.getValue().maxKeys()).isEqualTo(200);
    }

    @Test
    @DisplayName("S3 Object가 없으면 파일 없음 오류로 변환한다")
    void read_throwsNotFound_when_objectDoesNotExist() {
        given(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
            .willThrow(NoSuchKeyException.builder().message("missing").build());

        assertThatThrownBy(() -> storageService.read(STORED_FILE))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FILE_OBJECT_NOT_FOUND));
    }

    @Test
    @DisplayName("S3 SDK 읽기 장애는 저장소 사용 불가 오류로 변환한다")
    void read_throwsStorageFailure_when_sdkFails() {
        given(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
            .willThrow(IllegalStateException.class);

        assertThatThrownBy(() -> storageService.read(STORED_FILE))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FILE_STORAGE_FAILED));
    }

    @Test
    @DisplayName("전달된 Bucket과 Object Key의 S3 Object를 삭제한다")
    void delete_removesStoredObject() {
        ArgumentCaptor<DeleteObjectRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);

        storageService.delete(STORED_FILE);

        then(s3Client).should().deleteObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo(STORED_FILE.bucketName());
        assertThat(requestCaptor.getValue().key()).isEqualTo(STORED_FILE.objectKey());
    }

    @Test
    @DisplayName("S3가 아닌 Provider 위치를 읽으면 설정 불일치 오류가 발생한다")
    void read_throwsConfigurationMismatch_when_providerDoesNotMatch() {
        StoredFile localFile = new StoredFile(
            StorageProvider.LOCAL, STORED_FILE.bucketName(), STORED_FILE.objectKey()
        );

        assertThatThrownBy(() -> storageService.read(localFile))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.FILE_STORAGE_CONFIGURATION_MISMATCH));
    }

    @Test
    @DisplayName("현재 설정과 다른 Bucket 위치를 읽으면 설정 불일치 오류가 발생한다")
    void read_throwsConfigurationMismatch_when_bucketDoesNotMatch() {
        StoredFile otherBucketFile = new StoredFile(
            StorageProvider.S3, "other-bucket", STORED_FILE.objectKey()
        );

        assertThatThrownBy(() -> storageService.read(otherBucketFile))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.FILE_STORAGE_CONFIGURATION_MISMATCH));
    }
}
