package com.opensource.docgrid.domain.document.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

/**
 * 설정된 Local Root 아래에서 문서 원본을 저장·조회·목록·삭제하는 파일 저장소 Adapter다.
 * DB에는 Host 절대 경로 대신 논리 Bucket과 Object Key만 전달하며 Root 밖 경로와 설정 불일치를 차단한다.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "storage", name = "type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements FileStorageService {

    private final Path rootPath;
    private final String bucketName;

    /**
     * 설정 Bucket을 검증하고 Local Root를 생성·실경로 정규화해 이후 경로 검사의 기준으로 고정한다.
     */
    public LocalFileStorageService(FileStorageProperties properties) {
        this.bucketName = requireBucket(properties.getBucket());
        this.rootPath = prepareRoot(properties.getLocal().getRoot());
    }

    /**
     * 입력 Stream을 Root 내부 임시 파일에 기록한 뒤 최종 Object Key로 교체한다.
     */
    @Override
    public StoredFile store(InputStream inputStream, long fileSize, String contentType, String objectKey) {
        Path temporaryFile = null;
        try {
            // 1. Object Key의 부모를 준비하고 실제 경로가 설정 Root 안에 있는지 다시 확인한다.
            Path target = resolveWritablePath(objectKey);
            temporaryFile = Files.createTempFile(target.getParent(), ".docgrid-", ".tmp");

            // 2. 부분 파일이 최종 Key로 노출되지 않도록 임시 파일에 모두 쓴 뒤 교체한다.
            long copiedBytes = Files.copy(inputStream, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
            if (copiedBytes != fileSize) {
                throw new IOException("저장된 파일 크기가 요청 Metadata와 일치하지 않습니다.");
            }
            moveAtomically(temporaryFile, target);
            temporaryFile = null;
            return new StoredFile(StorageProvider.LOCAL, bucketName, objectKey);
        } catch (Exception exception) {
            deleteTemporaryFile(temporaryFile);
            log.error("Local 파일 저장에 실패했습니다.", exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    /**
     * 현재 Local 저장소에 속한 Object 전체를 Byte 배열로 읽는다.
     */
    @Override
    public byte[] read(StoredFile storedFile) {
        // 1. DB에 기록된 Provider와 Bucket이 현재 Adapter 설정과 같은지 확인한다.
        validateLocation(storedFile);

        // 2. Symbolic Link와 Root 이탈을 검사한 실제 파일만 읽고, 없음과 저장소 장애를 구분한다.
        try {
            Path target = resolveExistingPath(storedFile.objectKey());
            return Files.readAllBytes(target);
        } catch (NoSuchFileException exception) {
            throw new DocGridException(ErrorCode.FILE_OBJECT_NOT_FOUND, exception);
        } catch (Exception exception) {
            log.error("Local 파일 읽기에 실패했습니다.", exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    /**
     * 설정 Root 안의 접두사 경로를 Symbolic Link 없이 순회하고 일반 파일 Metadata만 반환한다.
     */
    @Override
    public Stream<StorageObjectMetadata> streamObjects(String prefix, int pageSize) {
        try {
            // 1. 외부 Adapter와 같은 계약을 유지하도록 접두사와 처리 크기를 먼저 검증한다.
            if (pageSize <= 0) {
                throw new IOException("Object 목록 Page 크기는 0보다 커야 합니다.");
            }
            Path prefixPath = resolvePath(prefix);
            if (Files.notExists(prefixPath, LinkOption.NOFOLLOW_LINKS)) {
                return Stream.empty();
            }
            if (Files.isSymbolicLink(prefixPath)
                || !Files.isDirectory(prefixPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Object 목록 접두사는 안전한 Directory여야 합니다.");
            }

            // 2. Files.walk의 지연 Stream을 그대로 전달해 전체 파일 목록을 메모리에 적재하지 않는다.
            return Files.walk(prefixPath)
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .map(this::toStorageObjectMetadata);
        } catch (Exception exception) {
            log.error("Local 파일 목록 조회에 실패했습니다.", exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    /**
     * 현재 Local 저장소에 속한 Object를 멱등 삭제한다.
     */
    @Override
    public void delete(StoredFile storedFile) {
        // 1. 다른 Provider 또는 Bucket의 경로를 이 Adapter가 삭제하지 못하도록 차단한다.
        validateLocation(storedFile);

        // 2. 존재하지 않으면 성공으로 끝내고, 존재하면 Link와 Root 경계를 검증한 뒤 삭제한다.
        try {
            Path target = resolvePath(storedFile.objectKey());
            if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            validateExistingPath(target);
            Files.deleteIfExists(target);
        } catch (Exception exception) {
            log.error("Local 파일 삭제에 실패했습니다.", exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    /**
     * 설정 Root를 생성하고 Symbolic Link가 해석된 절대 실경로로 고정한다.
     */
    private Path prepareRoot(Path configuredRoot) {
        if (configuredRoot == null) {
            throw new IllegalStateException("storage.local.root 설정이 필요합니다.");
        }
        try {
            Path normalizedRoot = configuredRoot.toAbsolutePath().normalize();
            Files.createDirectories(normalizedRoot);
            return normalizedRoot.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Local 파일 저장소 Root를 준비하지 못했습니다.", exception);
        }
    }

    /**
     * DB 저장 위치 식별에 사용할 논리 Bucket 설정이 비어 있지 않은지 검증한다.
     */
    private String requireBucket(String configuredBucket) {
        if (!StringUtils.hasText(configuredBucket)) {
            throw new IllegalStateException("storage.bucket 설정이 필요합니다.");
        }
        return configuredBucket;
    }

    /**
     * 안전한 부모 디렉터리를 준비하고 새 Object를 쓸 최종 경로를 반환한다.
     */
    private Path resolveWritablePath(String objectKey) throws IOException {
        Path target = resolvePath(objectKey);
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Object Key의 부모 경로를 확인할 수 없습니다.");
        }
        Path safeParent = createDirectoriesWithoutFollowingLinks(parent);
        return safeParent.resolve(target.getFileName());
    }

    /**
     * 기존 Object 경로의 존재, Link 여부와 Root 경계를 검증해 읽기 가능한 경로를 반환한다.
     */
    private Path resolveExistingPath(String objectKey) throws IOException {
        Path target = resolvePath(objectKey);
        if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(target.toString());
        }
        validateExistingPath(target);
        return target;
    }

    /**
     * Root부터 부모 경로까지 각 Segment를 생성하되 기존 Symbolic Link를 따라가지 않는다.
     *
     * <p>동시 요청이 같은 디렉터리를 먼저 생성할 수 있으므로 AlreadyExists는 허용한 뒤 실제로
     * Link가 아닌 디렉터리인지 매 단계 다시 확인한다.
     */
    private Path createDirectoriesWithoutFollowingLinks(Path parent) throws IOException {
        Path current = rootPath;
        for (Path segment : rootPath.relativize(parent)) {
            current = current.resolve(segment);
            try {
                Files.createDirectory(current);
            } catch (FileAlreadyExistsException ignored) {
                // 동시에 생성됐거나 이미 존재하는 경로도 아래의 no-follow 검증을 반드시 통과해야 한다.
            }
            if (Files.isSymbolicLink(current)
                || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Object Key의 부모 경로에 안전하지 않은 항목이 있습니다.");
            }
        }
        return current;
    }

    /**
     * 논리 Object Key를 정규화하고 절대 경로·상위 이동을 거부해 Root 내부 경로로 변환한다.
     */
    private Path resolvePath(String objectKey) throws IOException {
        if (!StringUtils.hasText(objectKey)) {
            throw new IOException("Object Key가 비어 있습니다.");
        }
        Path relativePath = Path.of(objectKey).normalize();
        if (relativePath.isAbsolute() || relativePath.startsWith("..")) {
            throw new IOException("Object Key가 Local 파일 저장소 Root를 벗어났습니다.");
        }
        Path target = rootPath.resolve(relativePath).normalize();
        if (!target.startsWith(rootPath)) {
            throw new IOException("Object Key가 Local 파일 저장소 Root를 벗어났습니다.");
        }
        return target;
    }

    /**
     * 기존 대상과 실경로 부모가 Symbolic Link를 통해 설정 Root 밖으로 벗어나지 않았는지 확인한다.
     */
    private void validateExistingPath(Path target) throws IOException {
        if (Files.isSymbolicLink(target)) {
            throw new IOException("Symbolic Link는 파일 저장 위치로 사용할 수 없습니다.");
        }
        Path realParent = target.getParent().toRealPath();
        if (!realParent.startsWith(rootPath)) {
            throw new IOException("Object Key가 Local 파일 저장소 Root를 벗어났습니다.");
        }
    }

    /**
     * 저장된 위치가 현재 LOCAL Provider와 설정 Bucket에 속하는지 검증한다.
     */
    private void validateLocation(StoredFile storedFile) {
        if (storedFile.storageProvider() == StorageProvider.LOCAL
            && bucketName.equals(storedFile.bucketName())) {
            return;
        }
        log.error(
            "현재 Local 저장소 설정과 파일 위치가 일치하지 않습니다."
        );
        throw new DocGridException(ErrorCode.FILE_STORAGE_CONFIGURATION_MISMATCH);
    }

    /**
     * 임시 파일을 가능한 경우 원자적으로 교체하고 파일 시스템 미지원 시 일반 교체로 대체한다.
     */
    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 저장 실패 뒤 남은 임시 파일을 최선 노력 방식으로 정리한다.
     */
    private void deleteTemporaryFile(Path temporaryFile) {
        if (temporaryFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryFile);
        } catch (IOException cleanupException) {
            log.warn("Local 임시 파일 정리에 실패했습니다.", cleanupException);
        }
    }

    /** Root 기준 상대 경로와 파일 속성을 저장소 중립 Metadata로 변환한다. */
    private StorageObjectMetadata toStorageObjectMetadata(Path path) {
        try {
            String objectKey = rootPath.relativize(path).toString()
                .replace(path.getFileSystem().getSeparator(), "/");
            Instant lastModified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant();
            return new StorageObjectMetadata(
                new StoredFile(StorageProvider.LOCAL, bucketName, objectKey),
                Files.size(path),
                lastModified
            );
        } catch (Exception exception) {
            log.error("Local 파일 Metadata 조회에 실패했습니다.", exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }
}
