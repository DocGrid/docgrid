package com.opensource.docgrid.domain.search.service.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.search.service.query.QueryTermExtractor.QueryTerm;

@DisplayName("QueryTermExtractor 단위 테스트")
class QueryTermExtractorTest {

    private static List<String> texts(String question) {
        return QueryTermExtractor.extract(question).stream().map(QueryTerm::text).toList();
    }

    @Test
    @DisplayName("정상 케이스: 조사를 떼고 질문 말투 단어를 버린다")
    void extract_stripsParticlesAndStopWords() {
        assertThat(texts("STOMP에서 /app은 어느 방향이야?")).containsExactly("stomp", "/app", "방향");
        assertThat(texts("OM은 뭐야?")).containsExactly("om");
    }

    @Test
    @DisplayName("정상 케이스: 영문·숫자 단어는 단어 경계 패턴, 그 밖의 단어는 부분 문자열 패턴이 된다")
    void extract_buildsRegexByTermKind() {
        List<QueryTerm> terms = QueryTermExtractor.extract("OM은 방향");

        assertThat(terms).extracting(QueryTerm::asciiWord).containsExactly(true, false);
        assertThat(terms.get(0).regex()).isEqualTo("(^|[^a-z0-9])om([^a-z0-9]|$)");
        assertThat(terms.get(1).regex()).isEqualTo("방향");
    }

    @Test
    @DisplayName("정상 케이스: 정규식 특수 문자는 이스케이프한다")
    void extract_escapesRegexSpecialCharacters() {
        QueryTerm term = QueryTermExtractor.extract("a+b* 확인").get(0);

        assertThat(term.text()).isEqualTo("a+b*");
        assertThat(term.regex()).isEqualTo("a\\+b\\*");
    }

    @Test
    @DisplayName("정상 케이스: 문장부호와 따옴표를 떼고 같은 단어가 반복되면 그대로 둔다")
    void extract_stripsPunctuationAndKeepsDuplicates() {
        assertThat(texts("`rmdir` 'rmdir' 뭐야?")).containsExactly("rmdir", "rmdir");
    }

    @Test
    @DisplayName("경계 케이스: 조사를 떼면 두 글자 미만이 되는 단어는 조사를 떼지 않는다")
    void extract_keepsTermWhenStrippingWouldLeaveTooShort() {
        assertThat(texts("회장은 누구야?")).containsExactly("회장", "누구");
        assertThat(texts("SSE는 약자야?")).containsExactly("sse", "약자");
    }

    @Test
    @DisplayName("경계 케이스: 한 글자 단어와 불용어만 있으면 빈 목록이다")
    void extract_onlyShortOrStopWords_returnsEmpty() {
        assertThat(QueryTermExtractor.extract("뭐 왜 어떻게?")).isEmpty();
        assertThat(QueryTermExtractor.extract("a b")).isEmpty();
    }

    @Test
    @DisplayName("예외 케이스: 공백이거나 null이면 빈 목록이다")
    void extract_blankOrNull_returnsEmpty() {
        assertThat(QueryTermExtractor.extract("   ")).isEmpty();
        assertThat(QueryTermExtractor.extract(null)).isEmpty();
    }
}
