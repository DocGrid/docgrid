package com.opensource.docgrid.domain.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ExchangeFunction;

import com.opensource.docgrid.domain.rag.dto.OllamaGenerateResult;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OllamaClient 단위 테스트")
class OllamaClientTest {

    @Mock private RestClient restClient;
    @Mock(answer = Answers.RETURNS_SELF) private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    private OllamaClient ollamaClient;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        ollamaClient = new OllamaClient(
            "qwen2.5:3b", "30m", 300, 0.3, 0.8, 1.1, 256, Duration.ofSeconds(25), restClient,
            meterRegistry
        );
        doReturn(requestBodyUriSpec).when(restClient).post();
    }

    /** exchange()에 넘어온 함수를 주어진 NDJSON 스트림 응답으로 즉시 실행하도록 스텁한다. */
    private void givenStreamBody(String ndjson) {
        givenStreamBody(new ByteArrayInputStream(ndjson.getBytes(StandardCharsets.UTF_8)));
    }

    private void givenStreamBody(InputStream body) {
        doAnswer(invocation -> {
            ExchangeFunction<?> fn = invocation.getArgument(0);
            ConvertibleClientHttpResponse response = mock(ConvertibleClientHttpResponse.class);
            doReturn(HttpStatus.OK).when(response).getStatusCode();
            doReturn(body).when(response).getBody();
            return fn.exchange(null, response);
        }).when(requestBodyUriSpec).exchange(any());
    }

    @Test
    @DisplayName("정상 케이스: NDJSON 청크를 누적해 답변 텍스트와 토큰 수를 반환한다")
    void generate_success() {
        givenStreamBody("""
            {"model":"qwen2.5:3b","created_at":"2026-08-16T00:00:00Z","response":"연차는 입사 1년 기준 ","done":false}
            {"model":"qwen2.5:3b","response":"15일 부여됩니다.","done":false}
            {"model":"qwen2.5:3b","response":"","done":true,"prompt_eval_count":120,"eval_count":45}
            """);

        OllamaGenerateResult result = ollamaClient.generate("질문: 연차 규정 알려줘");

        assertThat(result.model()).isEqualTo("qwen2.5:3b");
        assertThat(result.answerText()).isEqualTo("연차는 입사 1년 기준 15일 부여됩니다.");
        assertThat(result.inputTokenCount()).isEqualTo(120);
        assertThat(result.outputTokenCount()).isEqualTo(45);
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(meterRegistry.get("docgrid.ollama.generate.requests")
            .tag("outcome", "complete").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("docgrid.ollama.generate.duration")
            .tag("outcome", "complete").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.get("docgrid.ollama.generate.tokens")
            .tag("kind", "input").summary().totalAmount()).isEqualTo(120);
        assertThat(meterRegistry.get("docgrid.ollama.generate.tokens")
            .tag("kind", "output").summary().totalAmount()).isEqualTo(45);
    }

    @Test
    @DisplayName("토큰 상한 도달: eval_count가 num_predict 이상이면 잘림 안내 문구를 덧붙인다")
    void generate_hitsNumPredict_appendsTruncationNotice() {
        givenStreamBody("""
            {"model":"qwen2.5:3b","response":"1. 첫 항목 2. 둘째 항목","done":false}
            {"model":"qwen2.5:3b","response":"","done":true,"prompt_eval_count":120,"eval_count":300}
            """);

        OllamaGenerateResult result = ollamaClient.generate("질문: 명령어 다 알려줘");

        assertThat(result.answerText())
            .startsWith("1. 첫 항목 2. 둘째 항목")
            .contains("답변이 길어 일부 내용이 생략됐을 수 있습니다");
        assertThat(meterRegistry.get("docgrid.ollama.generate.requests")
            .tag("outcome", "partial").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("토큰 상한 도달: 단어 중간에서 끊긴 꼬리는 마지막 완결 문장까지만 남기고 잘라낸다")
    void generate_hitsNumPredict_trimsToLastSentence() {
        givenStreamBody("""
            {"model":"qwen2.5:3b","response":"5. 상태 확인은 `git status`를 사용합니다. 6. 병합하려면 `git merge` 명령","done":false}
            {"model":"qwen2.5:3b","response":"","done":true,"prompt_eval_count":120,"eval_count":300}
            """);

        OllamaGenerateResult result = ollamaClient.generate("질문: git 명령어 알려줘");

        assertThat(result.answerText())
            .startsWith("5. 상태 확인은 `git status`를 사용합니다.")
            .doesNotContain("6. 병합하려면")
            .contains("답변이 길어 일부 내용이 생략됐을 수 있습니다");
    }

    @Test
    @DisplayName("데드라인 초과: 스트림을 중단하고 그때까지 받은 부분 답변에 잘림 안내를 덧붙인다")
    void generate_deadlineExceeded_returnsPartialAnswer() {
        ollamaClient = new OllamaClient(
            "qwen2.5:3b", "30m", 300, 0.3, 0.8, 1.1, 256, Duration.ZERO, restClient,
            meterRegistry
        );
        doReturn(requestBodyUriSpec).when(restClient).post();
        givenStreamBody("""
            {"model":"qwen2.5:3b","response":"연차는 15일입니다. 그리고 추가","done":false}
            {"model":"qwen2.5:3b","response":"로 이월 규정이","done":false}
            {"model":"qwen2.5:3b","response":"","done":true,"prompt_eval_count":120,"eval_count":45}
            """);

        OllamaGenerateResult result = ollamaClient.generate("질문: 연차 규정 알려줘");

        assertThat(result.answerText())
            .startsWith("연차는 15일입니다.")
            .doesNotContain("이월 규정")
            .contains("답변이 길어 일부 내용이 생략됐을 수 있습니다");
    }

    @Test
    @DisplayName("서버 측 생성 취소: done 없이 스트림이 끝나면 마지막 완결 문장까지 남기고 잘림 안내를 덧붙인다")
    void generate_prematureStreamEnd_treatsAsTruncation() {
        givenStreamBody("""
            {"model":"qwen2.5:3b","response":"ls 명령어는 디렉토리 내용을 출력합니다. `ls ../test2` : 부모","done":false}
            """);

        OllamaGenerateResult result = ollamaClient.generate("질문: ls 명령어 알려줘");

        assertThat(result.answerText())
            .startsWith("ls 명령어는 디렉토리 내용을 출력합니다.")
            .doesNotContain("부모")
            .contains("답변이 길어 일부 내용이 생략됐을 수 있습니다");
    }

    @Test
    @DisplayName("스트림 정지: 읽기 중 IOException이 발생해도 이미 받은 부분 답변을 잘림으로 반환한다")
    void generate_streamStalled_returnsPartialAnswer() {
        byte[] data = "{\"model\":\"qwen2.5:3b\",\"response\":\"연차는 15일 부여됩니다. 이월 규\",\"done\":false}\n"
            .getBytes(StandardCharsets.UTF_8);
        InputStream stalledBody = new InputStream() {
            private int pos = 0;

            @Override
            public int read() throws IOException {
                if (pos < data.length) {
                    return data[pos++] & 0xFF;
                }
                throw new IOException("stream stalled");
            }
        };
        givenStreamBody(stalledBody);

        OllamaGenerateResult result = ollamaClient.generate("질문: 연차 규정 알려줘");

        assertThat(result.answerText())
            .startsWith("연차는 15일 부여됩니다.")
            .doesNotContain("이월 규")
            .contains("답변이 길어 일부 내용이 생략됐을 수 있습니다");
    }

    @Test
    @DisplayName("문장 경계 트리밍: 백틱 코드 스팬(`ls .`) 안의 마침표는 문장 끝으로 오인하지 않는다")
    void generate_trims_ignoresDotInsideInlineCode() {
        givenStreamBody("""
            {"model":"qwen2.5:3b","response":"1. `ls .` : 현재 디렉토리의 내용을 출력합니다. 2. `ls .` : 현재 디렉","done":false}
            """);

        OllamaGenerateResult result = ollamaClient.generate("질문: ls 명령어 알려줘");

        assertThat(result.answerText())
            .startsWith("1. `ls .` : 현재 디렉토리의 내용을 출력합니다.")
            .doesNotContain("2. `ls .`")
            .contains("답변이 길어 일부 내용이 생략됐을 수 있습니다");
    }

    @Test
    @DisplayName("언어 혼입: 답변에 섞인 한자/가나 문자를 제거하고 한국어만 남긴다")
    void generate_stripsForeignCjkCharacters() {
        givenStreamBody("""
            {"model":"qwen2.5:3b","response":"연차는 입사 1년 기준 15일 부여되며中文が混入 다음 해로 이월됩니다。","done":false}
            {"model":"qwen2.5:3b","response":"","done":true,"prompt_eval_count":120,"eval_count":45}
            """);

        OllamaGenerateResult result = ollamaClient.generate("질문: 연차 규정 알려줘");

        assertThat(result.answerText())
            .isEqualTo("연차는 입사 1년 기준 15일 부여되며 다음 해로 이월됩니다.")
            .doesNotContain("中文", "混入", "が", "。");
    }

    @Test
    @DisplayName("문장 경계 트리밍: 백틱 코드 스팬 안의 물음표는 문장 끝으로 오인하지 않는다")
    void generate_trims_ignoresQuestionMarkInsideInlineCode() {
        givenStreamBody("""
            {"model":"qwen2.5:3b","response":"포트 확인은 `netstat`을 사용합니다. 이후 `taskkill -F -PID <?","done":false}
            """);

        OllamaGenerateResult result = ollamaClient.generate("질문: 포트 죽이는 법 알려줘");

        assertThat(result.answerText())
            .startsWith("포트 확인은 `netstat`을 사용합니다.")
            .doesNotContain("<?")
            .contains("답변이 길어 일부 내용이 생략됐을 수 있습니다");
    }

    @Test
    @DisplayName("언어 혼입: 대량 혼입(중국어 반복 루프)은 혼입 시작 지점에서 잘라내고 잘림 안내를 덧붙인다")
    void generate_heavyCjkMixing_cutsAtMixingPoint() {
        givenStreamBody("""
            {"model":"qwen2.5:3b","response":"연차는 입사 1년 기준 15일 부여됩니다. 이월은 다음과 같습니다：第一条年假规定是这样的继续说明如下","done":false}
            {"model":"qwen2.5:3b","response":"","done":true,"prompt_eval_count":120,"eval_count":45}
            """);

        OllamaGenerateResult result = ollamaClient.generate("질문: 연차 규정 알려줘");

        assertThat(result.answerText())
            .startsWith("연차는 입사 1년 기준 15일 부여됩니다.")
            .doesNotContain("第一", "规定", "：")
            .contains("답변이 길어 일부 내용이 생략됐을 수 있습니다");
    }

    @Test
    @DisplayName("문장 경계 트리밍: 경로 표기(..)의 연속 마침표는 문장 끝으로 오인하지 않는다")
    void generate_trims_ignoresConsecutiveDots() {
        givenStreamBody("""
            {"model":"qwen2.5:3b","response":"4. `ls ..` : 부모 디렉토리의 내용을 출력합니다. 5. `ls ..","done":false}
            """);

        OllamaGenerateResult result = ollamaClient.generate("질문: ls 명령어 알려줘");

        assertThat(result.answerText())
            .startsWith("4. `ls ..` : 부모 디렉토리의 내용을 출력합니다.")
            .doesNotContain("5. `ls ..")
            .contains("답변이 길어 일부 내용이 생략됐을 수 있습니다");
    }

    @Test
    @DisplayName("서버 장애: RestClientException 발생 시 RAG_SERVICE_UNAVAILABLE 예외가 발생한다")
    void generate_serverUnavailable_throwsException() {
        doThrow(new ResourceAccessException("Connection refused"))
            .when(requestBodyUriSpec).exchange(any());

        assertThatThrownBy(() -> ollamaClient.generate("질문"))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RAG_SERVICE_UNAVAILABLE);
        assertThat(meterRegistry.get("docgrid.ollama.generate.requests")
            .tag("outcome", "failure").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("docgrid.ollama.generate.duration")
            .tag("outcome", "failure").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("빈 응답: 스트림에서 청크를 하나도 받지 못하면 RAG_SERVICE_UNAVAILABLE 예외가 발생한다")
    void generate_emptyStream_throwsException() {
        givenStreamBody("");

        assertThatThrownBy(() -> ollamaClient.generate("질문"))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RAG_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("빈 응답: 답변 텍스트 없이 done만 오면 RAG_SERVICE_UNAVAILABLE 예외가 발생한다")
    void generate_blankAnswer_throwsException() {
        givenStreamBody("""
            {"model":"qwen2.5:3b","response":"","done":true,"prompt_eval_count":10,"eval_count":0}
            """);

        assertThatThrownBy(() -> ollamaClient.generate("질문"))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RAG_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("서버 오류 상태: 5xx 응답이면 RAG_SERVICE_UNAVAILABLE 예외가 발생한다")
    void generate_errorStatus_throwsException() {
        doAnswer(invocation -> {
            ExchangeFunction<?> fn = invocation.getArgument(0);
            ConvertibleClientHttpResponse response = mock(ConvertibleClientHttpResponse.class);
            doReturn(HttpStatus.INTERNAL_SERVER_ERROR).when(response).getStatusCode();
            return fn.exchange(null, response);
        }).when(requestBodyUriSpec).exchange(any());

        assertThatThrownBy(() -> ollamaClient.generate("질문"))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RAG_SERVICE_UNAVAILABLE);
    }
}
