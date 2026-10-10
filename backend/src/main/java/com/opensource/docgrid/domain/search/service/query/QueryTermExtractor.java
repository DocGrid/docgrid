package com.opensource.docgrid.domain.search.service.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 질문에서 검색에 쓸 단어를 뽑아 DB 정규식 패턴으로 바꾼다.
 *
 * <p>소문자로 바꾸고 공백·문장부호로 나눈 뒤, 끝에 붙은 조사를 떼고 질문 말투 단어(불용어)와 한 글자 단어를 버린다.
 * 같은 단어가 여러 번 나오면 그대로 여러 개를 돌려주며 중복을 제거하지 않는다. 형태소 분석기를 쓰지 않는 단순 규칙이라
 * 조사 목록에 없는 어미나 복합어는 그대로 남는다. DB 조회나 가중치 계산은 하지 않는 순수 계산이고,
 * 단어별 가중치는 {@link HybridSearchQueryService}가 문서 빈도로 계산한다.
 */
public final class QueryTermExtractor {

    // 긴 조사부터 비교해 "에서는"이 "는"으로 잘못 떼어지지 않게 한다.
    private static final List<String> PARTICLE_SUFFIXES = List.of(
        "에서는", "으로는", "이라고", "에서", "으로", "까지", "부터", "이야", "이란", "란",
        "은", "는", "이", "가", "을", "를", "의", "와", "과", "도", "만", "에", "로", "야"
    );

    // 질문의 말투일 뿐 내용이 아닌 단어. 청크에 우연히 있어도 점수를 주지 않는다.
    private static final Set<String> STOP_WORDS = Set.of(
        "뭐", "뭐야", "어떻게", "어디서", "어느", "어디", "무슨", "언제", "왜", "몇", "얼마", "어떤",
        "뭐라고", "알려줘", "알려", "돼", "되", "해", "쓰는", "거야", "있어", "방법", "오늘", "내일"
    );

    private static final Pattern SPLIT = Pattern.compile("[^\\s?!.,()]+");
    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9]+");
    private static final int MIN_TERM_LENGTH = 2;
    private static final String REGEX_SPECIAL_CHARACTERS = "\\^$.|?*+()[]{}";

    private QueryTermExtractor() {
    }

    /**
     * 질문에서 검색 단어 목록을 뽑는다. 공백이거나 뽑을 단어가 없으면 빈 목록이다.
     */
    public static List<QueryTerm> extract(String question) {
        List<QueryTerm> terms = new ArrayList<>();
        if (question == null || question.isBlank()) {
            return terms;
        }
        Matcher matcher = SPLIT.matcher(question.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String term = stripQuotes(matcher.group());
            term = stripParticle(term);
            if (codePointLength(term) >= MIN_TERM_LENGTH && !STOP_WORDS.contains(term)) {
                terms.add(new QueryTerm(term, ASCII_WORD.matcher(term).matches()));
            }
        }
        return terms;
    }

    private static String stripQuotes(String token) {
        int start = 0;
        int end = token.length();
        while (start < end && isQuote(token.charAt(start))) {
            start++;
        }
        while (end > start && isQuote(token.charAt(end - 1))) {
            end--;
        }
        return token.substring(start, end);
    }

    private static boolean isQuote(char c) {
        return c == '\'' || c == '"' || c == '`';
    }

    private static String stripParticle(String term) {
        for (String suffix : PARTICLE_SUFFIXES) {
            if (term.endsWith(suffix) && codePointLength(term) - codePointLength(suffix) >= MIN_TERM_LENGTH) {
                return term.substring(0, term.length() - suffix.length());
            }
        }
        return term;
    }

    private static int codePointLength(String text) {
        return text.codePointCount(0, text.length());
    }

    /**
     * 질문에서 뽑은 단어 하나.
     *
     * @param text      소문자 단어
     * @param asciiWord 영문·숫자로만 이뤄졌는지. 이 경우 다른 영문·숫자에 붙어 있는 것({@code om}이 {@code computer} 안에 있는 경우)은 일치로 보지 않는다
     */
    public record QueryTerm(String text, boolean asciiWord) {

        /**
         * PostgreSQL {@code ~*}(대소문자 무시 정규식)에 쓸 패턴이다. 영문·숫자 단어는 단어 경계를 요구하고,
         * 그 밖의 단어(한글, 기호 포함)는 부분 문자열로 일치한다.
         */
        public String regex() {
            String escaped = escape(text);
            return asciiWord ? "(^|[^a-z0-9])" + escaped + "([^a-z0-9]|$)" : escaped;
        }

        private static String escape(String text) {
            StringBuilder builder = new StringBuilder();
            for (char c : text.toCharArray()) {
                if (REGEX_SPECIAL_CHARACTERS.indexOf(c) >= 0) {
                    builder.append('\\');
                }
                builder.append(c);
            }
            return builder.toString();
        }
    }
}
