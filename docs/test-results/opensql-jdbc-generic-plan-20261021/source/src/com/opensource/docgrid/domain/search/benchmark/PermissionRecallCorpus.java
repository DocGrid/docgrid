package com.opensource.docgrid.domain.search.benchmark;

import java.util.List;

/** 기존 #545 캐시의 값만 읽는 DTO. 파싱·모델 호출을 재실행하거나 캐시를 변경하지 않는다. */
record PermissionRecallCorpus(String embeddingModel, String createdAt, double embeddingSeconds,
        List<String> skippedSources, List<RealDocument> documents) {
    /** 기존 원본 메타데이터와 청크. 기존 Dataset의 동일 타입 경계를 유지한다. */
    record RealDocument(String sourcePath, String sourceSha256, String title, String documentType,
            List<RealChunk> chunks) { }

    /** 캐시의 float 값을 그대로 읽어 기존 결정적 합성 코드에 전달한다. */
    record RealChunk(int chunkIndex, String chunkText, int tokenCount, int charStart, int charEnd,
            Integer pageNo, String sectionTitle, String contentHash, String metadataJson, float[] vector) { }
}
