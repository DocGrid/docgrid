package com.opensource.docgrid.domain.rag.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.opensource.docgrid.domain.rag.dto.OllamaGenerateResult;
import com.opensource.docgrid.domain.rag.dto.request.OllamaGenerateRequest;
import com.opensource.docgrid.domain.rag.dto.request.OllamaGenerateRequest.OllamaGenerateOptions;
import com.opensource.docgrid.domain.rag.dto.response.OllamaGenerateResponse;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * Ollama {@code /api/generate}를 호출해 프롬프트로부터 답변을 생성하는 순수 HTTP 클라이언트 (F-RAG-02).
 *
 * <p>검색 결과가 있는지, LLM 호출을 생략할지(NO_CONTEXT) 판단하지 않는다 — 항상 주어진 프롬프트를 그대로
 * 전송한다. 그 판단은 이 클라이언트를 호출하는 쪽(RagFacade)의 책임이다.
 */
@Slf4j
@Service
public class OllamaClient {

    /**
     * eval_count(실제 생성된 토큰 수)가 num_predict에 도달했다는 건 모델이 할 말을 다 못 하고
     * 토큰 상한에 걸려 끊겼다는 확정적 신호다 — LLM이 스스로 이를 감지·보고하게 하는 것보다 신뢰할 수 있다.
     */
    private static final String TRUNCATION_NOTICE =
        "\n\n(※ 답변이 길어 일부 내용이 생략됐을 수 있습니다. 자세한 내용은 문서를 확인해주세요.)";

    // Ollama의 NDJSON 청크에는 created_at, total_duration 등 우리가 매핑하지 않는 필드가 있다.
    private static final ObjectMapper CHUNK_MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 한국어 RAG 답변에 한자·히라가나·가타카나가 나올 일은 없다. qwen 계열의 code-switching으로
     * 섞여 나온 문자를 프롬프트 지시(모델이 무시할 수 있음)가 아닌 코드로 제거한다.
     */
    private static final Pattern FOREIGN_CJK_PATTERN =
        Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]+");

    /**
     * 혼입이 이 글자 수를 넘으면 낱자 노이즈가 아니라 모델이 중국어로 넘어가 무너진 구간으로 판단하고,
     * 문자만 지워 구두점 뼈대를 남기는 대신 혼입 시작 지점에서 답변을 자른다.
     */
    private static final int FOREIGN_CJK_CUT_THRESHOLD = 8;

    private final String model;
    private final String keepAlive;
    private final int numPredict;
    private final double temperature;
    private final double topP;
    private final double repeatPenalty;
    private final int repeatLastN;
    private final Duration generateDeadline;
    private final RestClient restClient;

    public OllamaClient(
        @Value("${ollama.model}") String model,
        @Value("${ollama.keep-alive}") String keepAlive,
        @Value("${ollama.num-predict}") int numPredict,
        @Value("${ollama.temperature}") double temperature,
        @Value("${ollama.top-p}") double topP,
        @Value("${ollama.repeat-penalty}") double repeatPenalty,
        @Value("${ollama.repeat-last-n}") int repeatLastN,
        @Value("${ollama.generate-deadline}") Duration generateDeadline,
        @Qualifier("ollamaRestClient") RestClient restClient
    ) {
        this.model = model;
        this.keepAlive = keepAlive;
        this.numPredict = numPredict;
        this.temperature = temperature;
        this.topP = topP;
        this.repeatPenalty = repeatPenalty;
        this.repeatLastN = repeatLastN;
        this.generateDeadline = generateDeadline;
        this.restClient = restClient;
    }

    /**
     * 프롬프트를 Ollama에 스트리밍으로 전송하고, 청크를 누적하며 데드라인을 감시하다가 성공하면
     * 정제된 답변을, 실패하면 예외를 던진다.
     *
     * <p>성공 판정 이후 처리 순서: ①한 청크도 못 받았으면 실패 ②{@code done} 없이 끝났으면
     * 조기 종료로 기록(#210 PEG 파서 버그 추적용) ③언어 혼입 제거({@link #sanitizeAnswer}) ④
     * 정제 후 텍스트가 비었으면 실패 ⑤토큰 상한 도달·조기 종료·혼입 대량 컷 중 하나라도 해당하면
     * 문장 경계로 트리밍({@link #trimToSentenceBoundary}) + 잘림 안내 문구 부착. 이 순서가
     * 뒤바뀌면 안 된다 — 예를 들어 트리밍을 언어 혼입 제거보다 먼저 하면 아직 안 지워진 외국어
     * 글자를 문장 경계로 착각할 수 있다.
     */
    public OllamaGenerateResult generate(String prompt) {
        long start = System.currentTimeMillis();
        long deadline = start + generateDeadline.toMillis();

        StreamChunks chunks;
        try {
            chunks = restClient.post()
                .uri("/api/generate")
                /*
                 * 인자 순서 = model, prompt, stream, raw, keepAlive, options.
                 * stream=true, raw=true는 설정값이 아니라 이 메서드가 항상 지켜야 하는 고정
                 * 계약이라 하드코딩한다 — 아래 readStream()이 stream:true 응답을 전제로 짜여
                 * 있고, raw:true는 채팅 템플릿 오인식 버그(#210)를 피하려면 항상 켜져 있어야 한다.
                 */
                .body(new OllamaGenerateRequest(
                    model, prompt, true, true, keepAlive,
                    new OllamaGenerateOptions(numPredict, temperature, topP, repeatPenalty, repeatLastN)
                ))
                .exchange((request, response) -> {
                    if (response.getStatusCode().isError()) {
                        log.error("Ollama 서버 오류 응답: status={}", response.getStatusCode());
                        throw new DocGridException(ErrorCode.RAG_SERVICE_UNAVAILABLE);
                    }
                    return readStream(response.getBody(), deadline);
                });
        } catch (RestClientException e) {
            log.error("Ollama 서버 호출 실패: {}", e.getMessage());
            throw new DocGridException(ErrorCode.RAG_SERVICE_UNAVAILABLE);
        }

        // 한 토큰도 못 받았으면 부분 답변 반환 대신 예외를 던져 상위의 extractive fallback에 맡긴다.
        if (chunks.last() == null || chunks.answer().isBlank()) {
            log.error("Ollama 스트리밍 응답에서 답변을 받지 못함: deadlineExceeded={}", chunks.deadlineExceeded());
            throw new DocGridException(ErrorCode.RAG_SERVICE_UNAVAILABLE);
        }

        /**
         * done:true 없이 스트림이 끝나는 경우가 있다: 데드라인 조기 종료 외에도, Ollama의 PEG 파서가
         * 한글이 토큰 경계에서 바이트 단위로 쪼개진 출력을 파싱하지 못하고 생성을 취소하는 버그
         * (llama.cpp #24807)가 확인됐다. 발생 빈도를 추적할 수 있게 경고 로그를 남긴다.
         */
        boolean prematureEnd = !chunks.last().done();
        if (prematureEnd && !chunks.deadlineExceeded()) {
            log.warn("Ollama 스트림이 done 없이 조기 종료됨(서버 측 생성 취소 추정): 수신 텍스트 길이={}", chunks.answer().length());
        }

        SanitizedAnswer sanitized = sanitizeAnswer(chunks.answer());
        if (sanitized.text().isBlank()) {
            log.error("한자/가나 혼입 처리 후 답변이 비어 있음");
            throw new DocGridException(ErrorCode.RAG_SERVICE_UNAVAILABLE);
        }

        String answerText = sanitized.text();
        boolean hitTokenLimit = chunks.last().evalCount() != null && chunks.last().evalCount() >= numPredict;
        if (hitTokenLimit || prematureEnd || sanitized.cutAtMixing()) {
            answerText = trimToSentenceBoundary(answerText) + TRUNCATION_NOTICE;
        }

        int latencyMs = (int) (System.currentTimeMillis() - start);
        return new OllamaGenerateResult(
            chunks.last().model(), answerText, chunks.last().promptEvalCount(), chunks.last().evalCount(), latencyMs
        );
    }

    /**
     * NDJSON 스트림을 청크 단위로 읽어 답변을 누적한다. 데드라인을 넘기면 읽기를 중단하고
     * 그때까지 모인 부분 답변을 반환한다 — 전체 응답에 read-timeout을 걸던 방식과 달리,
     * 디코드가 느려져도 이미 생성된 내용을 잃지 않는다. 조기 반환으로 스트림이 닫히면
     * Ollama가 클라이언트 이탈을 감지하고 생성을 중단하므로 자원도 낭비되지 않는다.
     */
    private StreamChunks readStream(InputStream body, long deadline) throws IOException {
        StringBuilder answer = new StringBuilder();
        OllamaGenerateResponse last = null;
        boolean deadlineExceeded = false;
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                OllamaGenerateResponse chunk = CHUNK_MAPPER.readValue(line, OllamaGenerateResponse.class);
                if (chunk.response() != null) {
                    answer.append(chunk.response());
                }
                last = chunk;
                if (chunk.done()) {
                    break;
                }
                if (System.currentTimeMillis() >= deadline) {
                    deadlineExceeded = true;
                    break;
                }
            }
        } catch (IOException e) {
            /**
             * 스트림이 멈춰 read-timeout이 본문 연결을 끊는 경우 등. 이미 받은 부분 답변이 있으면
             * 버리지 않고 done 없는 조기 종료로 처리해 반환하고, 하나도 없을 때만 실패로 전파한다.
             */
            if (answer.isEmpty()) {
                throw e;
            }
            log.warn("Ollama 스트림 읽기 중단, 수신된 부분 답변 반환: 길이={}, 원인={}", answer.length(), e.getMessage());
        }
        return new StreamChunks(answer.toString(), last, deadlineExceeded);
    }

    private record StreamChunks(String answer, OllamaGenerateResponse last, boolean deadlineExceeded) {
    }

    /**
     * 답변에 섞인 한자/가나를 처리한다. 낱자 수준의 혼입은 해당 문자만 제거하고, 대량 혼입은
     * 모델이 중국어 반복 루프로 넘어간 것이므로 혼입 시작 지점에서 답변을 잘라 잘림으로 처리한다.
     * 발생 빈도를 추적할 수 있게 감지 시 경고 로그를 남긴다.
     */
    private static SanitizedAnswer sanitizeAnswer(String text) {
        // 전각 구두점은 문장 부호 역할을 유지해야 하므로 삭제하지 않고 반각으로 치환한다.
        String normalized = text
            .replace('。', '.').replace('、', ',').replace('：', ':')
            .replace('，', ',').replace('！', '!').replace('？', '?');
        Matcher matcher = FOREIGN_CJK_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return new SanitizedAnswer(normalized, false);
        }
        int firstMixIndex = matcher.start();
        int mixedCount = matcher.group().length();
        while (matcher.find()) {
            mixedCount += matcher.group().length();
        }
        if (mixedCount > FOREIGN_CJK_CUT_THRESHOLD) {
            log.warn("답변에 한자/가나 대량 혼입({}자) 감지, 혼입 시작 지점에서 잘라냄", mixedCount);
            return new SanitizedAnswer(normalized.substring(0, firstMixIndex), true);
        }
        log.warn("답변에 한자/가나 혼입({}자) 감지, 제거함", mixedCount);
        return new SanitizedAnswer(FOREIGN_CJK_PATTERN.matcher(normalized).replaceAll(""), false);
    }

    private record SanitizedAnswer(String text, boolean cutAtMixing) {
    }

    /**
     * 토큰 상한에 걸려 잘린 답변을 마지막 완결 문장까지만 남긴다. 단어 중간에서 뚝 끊긴 꼬리를
     * 제거해 의도적으로 요약한 것처럼 보이게 한다. 문장 경계를 하나도 못 찾으면 원문을 그대로
     * 반환한다.
     */
    private static String trimToSentenceBoundary(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if ((c == '!' || c == '?' || (c == '.' && isSentenceEndDot(text, i))) && !insideInlineCode(text, i)) {
                return text.substring(0, i + 1);
            }
        }
        return text;
    }

    /**
     * 이 위치의 마침표가 진짜 문장 끝인지 판별한다. 숫자 목록 마커("6.")나 경로 표기(".."
     * 등 연속 마침표)의 마침표는 문장 끝이 아니므로, 앞 글자가 숫자·마침표이거나 뒤 글자도
     * 마침표면 문장 끝 후보에서 제외한다.
     */
    private static boolean isSentenceEndDot(String text, int i) {
        boolean precededOk = i == 0
            || (text.charAt(i - 1) != '.' && !Character.isDigit(text.charAt(i - 1)));
        boolean followedOk = i == text.length() - 1 || text.charAt(i + 1) != '.';
        return precededOk && followedOk;
    }

    /**
     * 이 위치가 백틱 코드 스팬(예: {@code `taskkill /PID <?`}) 안인지 판별한다 — 스팬 내부의
     * 문장 부호는 문장 끝이 아니다. 판별 방법: 이 위치 이전에 나온 백틱 개수를 세어 홀수면
     * "아직 닫는 백틱을 못 만난" 상태이므로 스팬 내부로 본다.
     */
    private static boolean insideInlineCode(String text, int i) {
        int backticks = 0;
        for (int j = 0; j < i; j++) {
            if (text.charAt(j) == '`') {
                backticks++;
            }
        }
        return backticks % 2 == 1;
    }
}
