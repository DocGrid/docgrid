package com.opensource.docgrid.domain.document.storage;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalLong;

import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 문서 도메인에 파일 저장·조회·삭제 기능을 제공하는 저장소 Port다.
 * Provider SDK와 경로 규칙은 Adapter 내부에 한정하고 호출자는 불변 저장 위치만 전달한다.
 * 후보 복구용 기본 검사는 기존 read를 사용하며 GCS Adapter는 generation이 고정된 Stream으로 검사한다.
 */
public interface FileStorageService {

    StoredFile store(InputStream inputStream, long fileSize, String contentType, String objectKey);

    /**
     * 저장된 Object 전체를 읽고 호출자와 Storage Stream 수명 주기를 분리한 Byte 배열을 반환한다.
     *
     * @param storedFile 읽을 Bucket과 Object Key
     * @return Object 전체 Byte
     */
    byte[] read(StoredFile storedFile);

    void delete(StoredFile storedFile);

    /** 후보가 실제로 존재하는지 확인하고 바이트의 해시·크기를 반환한다. */
    default Optional<CandidateObjectSnapshot> inspectCandidate(StoredFile storedFile) {
        try {
            byte[] bytes = read(storedFile);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Optional.of(new CandidateObjectSnapshot(
                bytes.length, HexFormat.of().formatHex(digest.digest(bytes)), null
            ));
        } catch (DocGridException exception) {
            if (exception.getErrorCode() == ErrorCode.FILE_OBJECT_NOT_FOUND) {
                return Optional.empty();
            }
            throw exception;
        } catch (NoSuchAlgorithmException exception) {
            throw new DocGridException(ErrorCode.FILE_HASH_CALCULATION_FAILED, exception);
        }
    }

    /** 저장 직후 얻을 수 있는 객체 generation이다. 지원하지 않는 저장소는 빈 값을 반환한다. */
    default OptionalLong candidateGeneration(StoredFile storedFile) {
        return OptionalLong.empty();
    }

    /** 확인한 generation만 삭제한다. 지원하지 않는 저장소는 삭제하지 않고 false를 반환한다. */
    default boolean deleteCandidateIfGenerationMatches(StoredFile storedFile, long generation) {
        return false;
    }
}
