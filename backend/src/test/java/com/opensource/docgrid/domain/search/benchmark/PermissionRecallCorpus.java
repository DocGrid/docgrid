package com.opensource.docgrid.domain.search.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.service.DocumentChunkDraft;
import com.opensource.docgrid.domain.document.service.DocumentParserRegistry;
import com.opensource.docgrid.domain.document.service.FixedSizeChunker;
import com.opensource.docgrid.domain.document.service.ParsedDocument;
import com.opensource.docgrid.domain.document.service.SectionPath;
import com.opensource.docgrid.domain.embedding.client.EmbeddingClient;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedBatchServerResponse;
import com.opensource.docgrid.domain.embedding.service.AdaptiveEmbeddingBatchPlanner;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.ChunkSnapshot;

/**
 * 권한 필터 검색 누락 시험(#545)에 쓰는 실제 문서 Chunk와 BGE-M3 Embedding 묶음.
 *
 * <p>저장소 안의 팀 작성 문서(예제 문서 15건, {@code docs/design}·{@code docs/test-results} 최상위 Markdown)를
 * 운영과 같은 Parser·Chunker·Embedding 입력 규칙(Section 경로 포함)·Batch 계획으로 처리해 만든다.
 * Embedding 서버 호출은 오래 걸리므로 결과를 JSON 파일로 캐시하고, 원본 파일 SHA-256이 모두 같으면 다시 쓴다.
 * DB 적재와 합성 확장은 하지 않는다. 그 역할은 {@link PermissionRecallDataset}과 {@link PermissionRecallBenchmark}가 맡는다.
 *
 * @param embeddingModel   Embedding 서버가 응답한 모델 이름
 * @param createdAt        캐시 생성 시각(ISO-8601)
 * @param embeddingSeconds 전체 Chunk Embedding에 걸린 시간
 * @param skippedSources   파싱·Chunking에 실패해 제외한 원본 경로와 사유
 * @param documents        원본 경로 순으로 정렬한 실제 문서
 */
record PermissionRecallCorpus(
    String embeddingModel,
    String createdAt,
    double embeddingSeconds,
    List<String> skippedSources,
    List<RealDocument> documents
) {

    private static final String DEMO_CORPUS_DIRECTORY = "examples/demo-corpus";
    private static final List<String> MARKDOWN_DIRECTORIES = List.of("docs/design", "docs/test-results");
    private static final Map<String, DocumentType> DEMO_TYPES = Map.of(
        "pdf", DocumentType.PDF,
        "docx", DocumentType.DOCX,
        "hwpx", DocumentType.HWPX
    );

    /**
     * 실제 문서 한 건과 그 Chunk.
     *
     * @param sourcePath   저장소 루트 기준 원본 경로
     * @param sourceSha256 원본 파일 SHA-256
     * @param title        예제 문서는 manifest 제목, Markdown은 첫 번째 {@code # } 제목(없으면 파일 이름)
     * @param documentType 운영 Parser 선택에 쓴 문서 형식
     * @param chunks       Chunk Index 순 Chunk
     */
    record RealDocument(
        String sourcePath,
        String sourceSha256,
        String title,
        String documentType,
        List<RealChunk> chunks
    ) {
    }

    /**
     * 운영 Chunker가 만든 Chunk와 운영 Embedding 입력으로 만든 Vector.
     *
     * @param vector Embedding 서버가 돌려준 값 그대로(정규화하지 않음)
     */
    record RealChunk(
        int chunkIndex,
        String chunkText,
        int tokenCount,
        int charStart,
        int charEnd,
        Integer pageNo,
        String sectionTitle,
        String contentHash,
        String metadataJson,
        float[] vector
    ) {
    }

    /** 원본 파일 한 건의 경로·형식·제목. */
    record SourceFile(Path path, String relativePath, DocumentType documentType, String title) {
    }

    int chunkCount() {
        return documents.stream().mapToInt(document -> document.chunks().size()).sum();
    }

    /**
     * 캐시가 있고 원본 SHA-256이 모두 같으면 캐시를 읽고, 아니면 운영 처리 경로로 다시 만들어 저장한다.
     */
    static PermissionRecallCorpus loadOrBuild(
        Path repositoryRoot,
        Path cachePath,
        Pipeline pipeline,
        ObjectMapper objectMapper
    ) throws IOException {
        List<SourceFile> sources = listSources(repositoryRoot, objectMapper);
        if (Files.exists(cachePath)) {
            PermissionRecallCorpus cached = objectMapper.readValue(cachePath.toFile(), PermissionRecallCorpus.class);
            if (cached.matches(sources)) {
                return cached;
            }
        }
        PermissionRecallCorpus built = build(sources, pipeline);
        Files.createDirectories(cachePath.toAbsolutePath().getParent());
        objectMapper.writeValue(cachePath.toFile(), built);
        return built;
    }

    /**
     * 시험 대상 원본 목록을 정렬된 순서로 만든다. 예제 문서는 manifest 제목을 쓴다.
     */
    static List<SourceFile> listSources(Path repositoryRoot, ObjectMapper objectMapper) throws IOException {
        List<SourceFile> sources = new ArrayList<>();

        // 1. 예제 문서: 가상 회사 규정 PDF·DOCX·HWPX 15건 (팀 작성, 저작권 문제 없음)
        Path demoDirectory = repositoryRoot.resolve(DEMO_CORPUS_DIRECTORY);
        JsonNode manifest = objectMapper.readTree(demoDirectory.resolve("manifest.json").toFile());
        for (JsonNode entry : manifest.path("documents")) {
            String file = entry.path("file").asText();
            String extension = file.substring(file.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            sources.add(new SourceFile(
                demoDirectory.resolve(file),
                DEMO_CORPUS_DIRECTORY + "/" + file,
                DEMO_TYPES.get(extension),
                entry.path("title").asText()
            ));
        }

        // 2. 팀 작성 설계·시험 결과 Markdown (하위 evidence 폴더의 원시 로그는 제외)
        for (String directory : MARKDOWN_DIRECTORIES) {
            try (Stream<Path> files = Files.list(repositoryRoot.resolve(directory))) {
                for (Path file : files.filter(path -> path.toString().endsWith(".md")).sorted().toList()) {
                    sources.add(new SourceFile(
                        file,
                        directory + "/" + file.getFileName(),
                        DocumentType.MD,
                        markdownTitle(file)
                    ));
                }
            }
        }
        return List.copyOf(sources);
    }

    private boolean matches(List<SourceFile> sources) throws IOException {
        int matched = documents.size() + skippedSources.size();
        if (matched != sources.size()) {
            return false;
        }
        Map<String, String> cachedHashes = new HashMap<>();
        documents.forEach(document -> cachedHashes.put(document.sourcePath(), document.sourceSha256()));
        for (SourceFile source : sources) {
            String cachedHash = cachedHashes.get(source.relativePath());
            boolean skipped = skippedSources.stream().anyMatch(entry -> entry.startsWith(source.relativePath() + " "));
            if (!skipped && !sha256(Files.readAllBytes(source.path())).equals(cachedHash)) {
                return false;
            }
        }
        return true;
    }

    private static PermissionRecallCorpus build(List<SourceFile> sources, Pipeline pipeline) throws IOException {
        List<RealDocument> documents = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        String model = null;
        long startedAt = System.nanoTime();

        for (SourceFile source : sources) {
            byte[] content = Files.readAllBytes(source.path());
            List<DocumentChunkDraft> drafts;
            try {
                // 1. 운영 DocumentParsingService와 같은 순서로 파싱한 뒤 Segment 경계를 지키며 Chunking한다.
                ParsedDocument parsed = pipeline.parserRegistry().parse(source.documentType(), content);
                drafts = pipeline.chunker().chunk(parsed);
            } catch (RuntimeException exception) {
                skipped.add(source.relativePath() + " " + exception.getClass().getSimpleName());
                continue;
            }
            if (drafts.isEmpty()) {
                skipped.add(source.relativePath() + " EMPTY");
                continue;
            }

            // 2. 운영 Embedding 준비 단계처럼 Section 경로를 붙인 입력을 만들고 같은 Batch 계획으로 나눈다.
            List<ChunkSnapshot> snapshots = new ArrayList<>(drafts.size());
            for (DocumentChunkDraft draft : drafts) {
                snapshots.add(new ChunkSnapshot(
                    (long) draft.chunkIndex(),
                    draft.chunkIndex(),
                    draft.chunkText(),
                    SectionPath.embeddingInput(draft.metadataJson(), draft.chunkText()),
                    draft.tokenCount(),
                    draft.contentHash()
                ));
            }
            float[][] vectors = new float[drafts.size()][];
            for (List<ChunkSnapshot> batch : pipeline.batchPlanner().plan(snapshots)) {
                EmbedBatchServerResponse response = pipeline.embeddingClient().embedBatch(
                    batch.stream().map(ChunkSnapshot::embeddingText).toList(),
                    batch.size()
                );
                model = response.model();
                for (int index = 0; index < batch.size(); index++) {
                    vectors[batch.get(index).chunkIndex()] = response.embeddings().get(index).vector();
                }
            }

            List<RealChunk> chunks = new ArrayList<>(drafts.size());
            for (DocumentChunkDraft draft : drafts) {
                chunks.add(new RealChunk(
                    draft.chunkIndex(), draft.chunkText(), draft.tokenCount(), draft.charStart(), draft.charEnd(),
                    draft.pageNo(), draft.sectionTitle(), draft.contentHash(), draft.metadataJson(),
                    vectors[draft.chunkIndex()]
                ));
            }
            documents.add(new RealDocument(
                source.relativePath(), sha256(content), source.title(), source.documentType().name(), List.copyOf(chunks)
            ));
        }

        double seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
        return new PermissionRecallCorpus(model, Instant.now().toString(), seconds, List.copyOf(skipped),
            List.copyOf(documents));
    }

    private static String markdownTitle(Path file) throws IOException {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.filter(line -> line.startsWith("# "))
                .findFirst()
                .map(line -> line.substring(2).strip())
                .orElse(file.getFileName().toString());
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 운영 문서 처리에 쓰는 Spring Bean 묶음. */
    record Pipeline(
        DocumentParserRegistry parserRegistry,
        FixedSizeChunker chunker,
        AdaptiveEmbeddingBatchPlanner batchPlanner,
        EmbeddingClient embeddingClient
    ) {
    }
}
