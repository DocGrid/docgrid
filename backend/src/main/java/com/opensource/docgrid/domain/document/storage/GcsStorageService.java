package com.opensource.docgrid.domain.document.storage;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalLong;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.opensource.docgrid.domain.document.config.FileStorageProperties;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * Google Cloud Storage에 문서 원본을 저장·조회·삭제하는 파일 저장소 Adapter다.
 * Bucket 생성과 IAM은 인프라가 담당하며 DB에 기록된 Provider와 Bucket 경계를 지킨다.
 * 중단된 업로드 후보는 읽은 generation에 고정해 해시를 확인하고 같은 generation만 조건부 삭제한다.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "storage", name = "type", havingValue = "gcs")
public class GcsStorageService implements FileStorageService {

    private final Storage storage;
    private final String bucketName;

    public GcsStorageService(Storage storage, FileStorageProperties fileStorageProperties) {
        this.storage = storage;
        this.bucketName = requireBucket(fileStorageProperties.getBucket());
    }

    /**
     * Object를 스트리밍으로 저장하고 이후 조회에 필요한 논리 위치를 반환한다.
     */
    @Override
    public StoredFile store(InputStream inputStream, long fileSize, String contentType, String objectKey) {
        BlobId blobId = BlobId.of(bucketName, objectKey);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType(contentType).build();
        try {
            // 1. 같은 Key의 기존 Object를 덮어쓰지 않도록 생성 조건을 적용한다.
            try (OutputStream output = Channels.newOutputStream(
                storage.writer(blobInfo, Storage.BlobWriteOption.doesNotExist()))) {
                // 2. 업로드 원본을 메모리에 전부 적재하지 않고 GCS로 전송한다.
                inputStream.transferTo(output);
            }
            // 3. DB에 저장할 Provider·Bucket·Key만 호출자에게 돌려준다.
            return new StoredFile(StorageProvider.GCS, bucketName, objectKey);
        } catch (Exception exception) {
            log.error("GCS 파일 저장에 실패했습니다. 오류종류={}", exception.getClass().getSimpleName());
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    /**
     * DB Snapshot의 위치가 현재 설정에 속할 때만 Object 전체를 읽는다.
     */
    @Override
    public byte[] read(StoredFile storedFile) {
        validateLocation(storedFile);
        try {
            return storage.readAllBytes(BlobId.of(storedFile.bucketName(), storedFile.objectKey()));
        } catch (StorageException exception) {
            if (exception.getCode() == 404) {
                throw new DocGridException(ErrorCode.FILE_OBJECT_NOT_FOUND, exception);
            }
            log.error("GCS 파일 읽기에 실패했습니다. 상태코드={}", exception.getCode());
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        } catch (Exception exception) {
            log.error("GCS 파일 읽기에 실패했습니다. 오류종류={}", exception.getClass().getSimpleName());
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    /**
     * 현재 설정과 일치하는 Object만 삭제한다. 없는 Object의 삭제는 완료로 취급한다.
     */
    @Override
    public void delete(StoredFile storedFile) {
        validateLocation(storedFile);
        try {
            storage.delete(BlobId.of(storedFile.bucketName(), storedFile.objectKey()));
        } catch (Exception exception) {
            log.error("GCS 파일 삭제에 실패했습니다. 오류종류={}", exception.getClass().getSimpleName());
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    /** 후보의 특정 generation을 스트리밍으로 읽어 크기와 SHA-256을 확인한다. */
    @Override
    public Optional<CandidateObjectSnapshot> inspectCandidate(StoredFile storedFile) {
        validateLocation(storedFile);
        try {
            Blob blob = storage.get(BlobId.of(storedFile.bucketName(), storedFile.objectKey()));
            if (blob == null) {
                return Optional.empty();
            }
            long generation = blob.getGeneration();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            // 메타데이터를 읽은 바로 그 generation만 열어 뒤늦은 객체 교체와 혼동하지 않는다.
            try (InputStream input = Channels.newInputStream(storage.reader(
                BlobId.of(storedFile.bucketName(), storedFile.objectKey(), generation),
                Storage.BlobSourceOption.generationMatch()
            ))) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                    size += read;
                }
            }
            return Optional.of(new CandidateObjectSnapshot(size, HexFormat.of().formatHex(digest.digest()), generation));
        } catch (StorageException exception) {
            if (exception.getCode() == 404) {
                return Optional.empty();
            }
            log.error("GCS 후보 검사에 실패했습니다. 상태코드={}", exception.getCode());
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new DocGridException(ErrorCode.FILE_HASH_CALCULATION_FAILED, exception);
        } catch (Exception exception) {
            log.error("GCS 후보 검사에 실패했습니다. 오류종류={}", exception.getClass().getSimpleName());
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    /** 저장 직후 객체의 generation을 읽어 후보 원장에 보존한다. */
    @Override
    public OptionalLong candidateGeneration(StoredFile storedFile) {
        validateLocation(storedFile);
        try {
            Blob blob = storage.get(BlobId.of(storedFile.bucketName(), storedFile.objectKey()));
            if (blob == null) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(blob.getGeneration());
        } catch (StorageException exception) {
            log.error("GCS 후보 generation 조회에 실패했습니다. 상태코드={}", exception.getCode());
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    /** 이름이 같더라도 검사한 generation이 바뀌었다면 삭제하지 않는다. */
    @Override
    public boolean deleteCandidateIfGenerationMatches(StoredFile storedFile, long generation) {
        validateLocation(storedFile);
        try {
            boolean deleted = storage.delete(
                BlobId.of(storedFile.bucketName(), storedFile.objectKey()),
                Storage.BlobSourceOption.generationMatch(generation)
            );
            return deleted || storage.get(BlobId.of(storedFile.bucketName(), storedFile.objectKey())) == null;
        } catch (StorageException exception) {
            if (exception.getCode() == 404) {
                return true;
            }
            if (exception.getCode() == 412) {
                return false;
            }
            log.error("GCS 후보 조건부 삭제에 실패했습니다. 상태코드={}", exception.getCode());
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    private String requireBucket(String configuredBucket) {
        if (!StringUtils.hasText(configuredBucket)) {
            throw new IllegalStateException("GCS Adapter에는 STORAGE_BUCKET 설정이 필요합니다.");
        }
        return configuredBucket;
    }

    private void validateLocation(StoredFile storedFile) {
        if (storedFile.storageProvider() == StorageProvider.GCS
            && bucketName.equals(storedFile.bucketName())) {
            return;
        }
        log.error("현재 GCS 저장소 설정과 파일 위치가 일치하지 않습니다.");
        throw new DocGridException(ErrorCode.FILE_STORAGE_CONFIGURATION_MISMATCH);
    }
}
