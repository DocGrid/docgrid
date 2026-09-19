package com.opensource.docgrid.domain.document.storage;

import java.io.InputStream;
import java.util.stream.Stream;

/**
 * 문서 도메인에 파일 저장·조회·목록·삭제 기능을 제공하는 저장소 Port다.
 * Provider SDK와 경로 규칙은 Adapter 내부에 한정하고 호출자는 불변 저장 위치만 전달한다.
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

    /**
     * 현재 Adapter의 Bucket에서 접두사가 일치하는 Object Metadata를 지연 조회한다.
     *
     * <p>호출자는 반환 Stream을 닫아 Local Filesystem과 Provider SDK의 조회 자원을 해제해야 한다.
     * {@code pageSize}는 외부 저장소의 한 요청 크기와 상위 계층의 DB Bulk 비교 크기를 제한한다.
     *
     * @param prefix 조회할 Object Key 접두사
     * @param pageSize 한 번에 처리할 최대 Object 수
     * @return Object Metadata Stream
     */
    Stream<StorageObjectMetadata> streamObjects(String prefix, int pageSize);

    void delete(StoredFile storedFile);
}
