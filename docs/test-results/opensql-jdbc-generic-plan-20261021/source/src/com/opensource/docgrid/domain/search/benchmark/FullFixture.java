package com.opensource.docgrid.domain.search.benchmark;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.search.benchmark.PermissionRecallDataset.DocumentSpec;
import com.opensource.docgrid.domain.search.benchmark.PermissionRecallCorpus.RealChunk;

/** 기존 캐시·Dataset·기준 스키마로 전용 DB만 적재한다. 공개 DB 접근과 모델 호출은 수행하지 않는다. */
public final class FullFixture {
    private static final String SCHEMA = "docgrid_permission_recall_545";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 원래 재현 질문 D01의 캐시 값을 Float.toString으로 원래 바인딩과 같이 만든다. */
    public static String questionVector() throws Exception {
        JsonNode questions = JSON.readTree(Path.of(System.getenv("GP_QUESTIONS_JSON")).toFile());
        String text = null;
        for (JsonNode q : questions.path("questions")) {
            if (q.path("id").asText().equals("D01")) text = q.path("question").asText();
        }
        if (text == null) throw new IllegalStateException("D01_missing");
        JsonNode v = JSON.readTree(Path.of(System.getenv("GP_QUESTION_CACHE")).toFile()).path("vectors").get(text);
        if (v == null || v.size() != 1024) throw new IllegalStateException("question_dimension_mismatch");
        float[] values = JSON.treeToValue(v, float[].class);
        return vector(values);
    }

    /** 마이그레이션·적재·인덱스·통계가 모두 완료된 뒤에만 최종 완료 이벤트를 출력한다. */
    public static void seed(Connection c) throws Exception {
        PermissionRecallCorpus corpus = JSON.readValue(Path.of(System.getenv("GP_CORPUS_CACHE")).toFile(), PermissionRecallCorpus.class);
        PermissionRecallDataset dataset = PermissionRecallDataset.expand(corpus.documents(), 50000,
                0.7593591047524721, 0.835336629551107, 545L);
        if (corpus.documents().size() != 251 || dataset.realChunkCount() != 3572
                || dataset.documents().size() != 3559 || dataset.chunkCount() != 50008) {
            throw new IllegalStateException("original_fixture_count_mismatch");
        }
        // 1. 기존 시험 커밋의 마이그레이션 전체를 신규 논리 DB 안의 같은 스키마에 적용한다.
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + SCHEMA);
            s.execute("SET search_path=" + SCHEMA + ",public");
            s.execute("SET statement_timeout='600s'");
            List<Path> migrations;
            try (var paths = Files.list(Path.of(System.getenv("GP_SCHEMA_DIR")))) {
                migrations = paths.filter(p -> p.getFileName().toString().matches("V[0-9]+__.*\\.sql"))
                        .sorted(Comparator.comparingInt(p -> Integer.parseInt(p.getFileName().toString().split("__")[0].substring(1)))).toList();
            }
            for (Path file : migrations) {
                System.out.println(JSON.writeValueAsString(Map.of("이벤트", "마이그레이션", "파일", file.getFileName().toString())));
                System.out.flush();
                s.execute(Files.readString(file));
            }
            try (var paths = Files.list(Path.of(System.getenv("GP_SCHEMA_DIR")))) {
                for (Path file : paths.filter(p -> p.getFileName().toString().startsWith("R__")).sorted().toList()) s.execute(Files.readString(file));
            }
            c.commit();
            // 2. 원래 Benchmark와 같은 초기화·문서/버전 연결·COPY 순서를 사용한다.
            s.execute("TRUNCATE embeddings,document_chunks,document_versions,documents,users RESTART IDENTITY CASCADE");
            s.execute("DROP INDEX " + SCHEMA + ".embeddings_vector_idx");
            s.execute("INSERT INTO users(email,password_hash,name,status) VALUES('fixture@example.invalid','not-an-authentication-password','Permission Recall Fixture','ACTIVE')");
            long model;
            try (ResultSet r = s.executeQuery("SELECT id FROM embedding_models WHERE is_active AND is_searchable")) { r.next(); model = r.getLong(1); }
            PGConnection pg = c.unwrap(PGConnection.class);
            CopyIn documents = pg.getCopyAPI().copyIn("COPY documents(id,owner_user_id,title,document_type,source_type,status,visibility) FROM STDIN CSV");
            for (DocumentSpec spec : dataset.documents()) write(documents, List.of(spec.index()+1L,1,title(dataset,spec),dataset.source(spec).documentType(),"UPLOAD","INDEXED","PRIVATE"));
            documents.endCopy();
            CopyIn versions = pg.getCopyAPI().copyIn("COPY document_versions(id,document_id,version_no,title_snapshot,content_type,status,created_by) FROM STDIN CSV");
            for (DocumentSpec spec : dataset.documents()) write(versions, List.of(spec.index()+1L,spec.index()+1L,1,title(dataset,spec),"text/plain","INDEXED",1));
            versions.endCopy(); s.execute("UPDATE documents SET current_version_id=id");
            CopyIn chunks = pg.getCopyAPI().copyIn("COPY document_chunks(id,document_version_id,chunk_index,chunk_text,token_count,char_start,char_end,page_no,section_title,content_hash,metadata_json) FROM STDIN CSV");
            long id = 0;
            for (DocumentSpec spec : dataset.documents()) for (RealChunk x : dataset.source(spec).chunks()) {
                write(chunks, java.util.Arrays.asList(++id,spec.index()+1L,x.chunkIndex(),x.chunkText(),x.tokenCount(),x.charStart(),x.charEnd(),x.pageNo(),x.sectionTitle(),x.contentHash(),x.metadataJson()));
            }
            chunks.endCopy();
            CopyIn embeddings = pg.getCopyAPI().copyIn("COPY embeddings(id,chunk_id,document_id,document_version_id,embedding_model_id,vector,dimension,vector_hash,status) FROM STDIN CSV");
            id=0; MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for (DocumentSpec spec : dataset.documents()) {
                for (int k=0;k<dataset.source(spec).chunks().size();k++) {
                    id++; float[] values=dataset.vector(spec,k);String vector=vector(values);digest.update(vector.getBytes(StandardCharsets.UTF_8));
                    java.nio.ByteBuffer binary=java.nio.ByteBuffer.allocate(values.length*Float.BYTES).order(java.nio.ByteOrder.BIG_ENDIAN);
                    for(float value:values)binary.putFloat(value);
                    String vectorHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary.array()));
                    write(embeddings,List.of(id,id,spec.index()+1L,spec.index()+1L,model,vector,1024,vectorHash,"ACTIVE"));
                }
                if ((spec.index()+1)%500==0) {
                    System.out.println(JSON.writeValueAsString(Map.of("이벤트","적재 진행","완료문서수",spec.index()+1,"완료청크수",id))); System.out.flush();
                }
            }
            embeddings.endCopy();
            // 3. 원래 정의로 HNSW를 만들고 원래와 같은 순서로 통계를 갱신한다.
            s.execute("ANALYZE documents");s.execute("ANALYZE document_versions");s.execute("ANALYZE document_chunks");
            System.out.println(JSON.writeValueAsString(Map.of("이벤트","HNSW 생성 시작","청크수",id)));System.out.flush();
            s.execute("CREATE INDEX embeddings_vector_idx ON " + SCHEMA + ".embeddings USING hnsw(vector vector_cosine_ops)");
            s.execute("ANALYZE embeddings");c.commit();
            try (ResultSet r=s.executeQuery("SELECT count(*),pg_relation_size('embeddings_vector_idx'),pg_total_relation_size('embeddings'),pg_database_size(current_database()) FROM embeddings")) {
                r.next();System.out.println(JSON.writeValueAsString(Map.of("이벤트","동일 조건 적재 완료","문서수",dataset.documents().size(),"청크수",r.getLong(1),"HNSW_bytes",r.getLong(2),"embeddings_total_bytes",r.getLong(3),"DB_bytes",r.getLong(4),"생성벡터_SHA256",HexFormat.of().formatHex(digest.digest()))));System.out.flush();
            }
        } catch (Exception e) { c.rollback(); throw e; }
    }

    private static String title(PermissionRecallDataset d, DocumentSpec s) {
        String title=d.source(s).title()+(s.synthetic()?" · 합성 "+s.index():"");
        return title.length()>480?title.substring(0,480):title;
    }
    private static String vector(float[] values) { StringJoiner j=new StringJoiner(",","[","]");for(float v:values)j.add(Float.toString(v));return j.toString(); }
    private static void write(CopyIn copy,List<?> values) throws Exception {
        StringJoiner csv=new StringJoiner(",");
        for(Object value:values) csv.add(value==null?"":value instanceof Number?value.toString():"\""+value.toString().replace("\"","\"\"")+"\"");
        byte[] bytes=(csv+"\n").getBytes(StandardCharsets.UTF_8);copy.writeToCopy(bytes,0,bytes.length);
    }
}
