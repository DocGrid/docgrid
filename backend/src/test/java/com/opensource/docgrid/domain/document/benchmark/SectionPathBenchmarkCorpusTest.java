package com.opensource.docgrid.domain.document.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.document.benchmark.SectionPathBenchmarkCorpus.BenchmarkDocument;
import com.opensource.docgrid.domain.document.benchmark.SectionPathBenchmarkCorpus.QueryCase;
import com.opensource.docgrid.domain.document.benchmark.SectionPathBenchmarkCorpus.Section;
import com.opensource.docgrid.domain.document.service.DocxDocumentParser;
import com.opensource.docgrid.domain.document.service.MarkdownDocumentParser;
import com.opensource.docgrid.domain.document.service.ParsedDocument;
import com.opensource.docgrid.domain.document.service.ParsedDocumentSegment;
import com.opensource.docgrid.domain.document.service.SectionPath;
import com.opensource.docgrid.domain.document.service.TextDocumentParser;

/**
 * Section 경로 Benchmark 코퍼스가 결정적이고, 정답이 하나뿐이며, 두 형식에서 같은 경로를 만드는지 모델 없이 검증한다.
 */
@DisplayName("Section 경로 Benchmark 코퍼스 테스트")
class SectionPathBenchmarkCorpusTest {

    @Test
    @DisplayName("질의마다 정답 근거는 전체 문서 중 정확히 한 조항 본문에만 있다")
    void queries_haveExactlyOneRelevantSection() {
        List<BenchmarkDocument> documents = SectionPathBenchmarkCorpus.documents();

        assertThat(documents).hasSize(4);
        assertThat(SectionPathBenchmarkCorpus.queries()).hasSize(24);
        for (QueryCase query : SectionPathBenchmarkCorpus.queries()) {
            long matchingSections = documents.stream()
                .flatMap(document -> document.sections().stream())
                .filter(section -> section.body().contains(query.evidence()))
                .count();
            assertThat(matchingSections).as(query.queryId()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("같은 입력이면 문서와 질의가 항상 같다")
    void corpus_isDeterministic() {
        assertThat(SectionPathBenchmarkCorpus.documents()).isEqualTo(SectionPathBenchmarkCorpus.documents());
        assertThat(SectionPathBenchmarkCorpus.queries()).isEqualTo(SectionPathBenchmarkCorpus.queries());
        BenchmarkDocument document = SectionPathBenchmarkCorpus.documents().get(0);
        assertThat(SectionPathBenchmarkCorpus.toMarkdown(document)).isEqualTo(SectionPathBenchmarkCorpus.toMarkdown(document));
    }

    @Test
    @DisplayName("DOCX와 Markdown 모두 모든 조항에서 문서 제목 > 조 > 항 경로를 만든다")
    void parsers_buildSamePathForBothFormats() {
        for (BenchmarkDocument document : SectionPathBenchmarkCorpus.documents()) {
            ParsedDocument docx = new DocxDocumentParser().parseDocument(SectionPathBenchmarkCorpus.toDocx(document));
            ParsedDocument markdown = new MarkdownDocumentParser(new TextDocumentParser())
                .parseDocument(SectionPathBenchmarkCorpus.toMarkdown(document));

            for (Section section : document.sections()) {
                List<String> expected = List.of(document.title(), section.groupTitle(), section.leafTitle());
                assertThat(headingsOf(docx, section.leafTitle())).as(document.documentId() + " docx").isEqualTo(expected);
                assertThat(headingsOf(markdown, section.leafTitle())).as(document.documentId() + " md").isEqualTo(expected);
            }
        }
    }

    private List<String> headingsOf(ParsedDocument parsed, String leafTitle) {
        ParsedDocumentSegment segment = parsed.segments().stream()
            .filter(candidate -> leafTitle.equals(candidate.sectionTitle()))
            .findFirst()
            .orElseThrow();
        return SectionPath.readHeadings(segment.metadataJson()).orElseThrow();
    }
}
