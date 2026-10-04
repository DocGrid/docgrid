package com.opensource.docgrid.domain.rag.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.rag.dto.OllamaGenerateResult;
import com.opensource.docgrid.global.config.OllamaServerConfig;
import com.sun.net.httpserver.HttpServer;

/**
 * 운영 설정({@link OllamaServerConfig})으로 만든 실제 RestClient가 Ollama에 보내는 요청의 전송 형식을 검증한다.
 *
 * <p>{@code jackson-dataformat-xml}이 Classpath에 있으면 Content-Type을 지정하지 않은 요청이 XML로 나가고,
 * Ollama가 이를 거부하면 RAG는 오류 없이 추출형 Fallback으로 대체돼 증상이 드러나지 않는다. 로컬 HTTP 서버가
 * 받은 실제 요청으로 그 회귀를 잡는다.
 */
@DisplayName("OllamaClient 전송 계약 테스트")
class OllamaClientWireContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private HttpServer server;
    private OllamaClient ollamaClient;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                {"model":"qwen2.5:3b","response":"연차는 15일입니다.","done":false}
                {"model":"qwen2.5:3b","response":"","done":true,"prompt_eval_count":10,"eval_count":5}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        OllamaServerConfig config = new OllamaServerConfig();
        ReflectionTestUtils.setField(config, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(config, "connectTimeout", Duration.ofSeconds(2));
        ReflectionTestUtils.setField(config, "readTimeout", Duration.ofSeconds(5));
        ollamaClient = new OllamaClient(
            "qwen2.5:3b", "30m", 300, 0.3, 0.8, 1.1, 256, Duration.ofSeconds(25), config.ollamaRestClient()
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("생성 요청은 JSON으로 전송한다")
    void generate_sendsJsonRequest() throws IOException {
        OllamaGenerateResult result = ollamaClient.generate("질문: 연차 규정 알려줘");

        assertThat(result.answerText()).contains("연차는 15일입니다.");
        assertThat(contentType.get()).startsWith("application/json");
        JsonNode body = objectMapper.readTree(requestBody.get());
        assertThat(body.get("model").asText()).isEqualTo("qwen2.5:3b");
        assertThat(body.get("prompt").asText()).isEqualTo("질문: 연차 규정 알려줘");
        assertThat(body.get("stream").asBoolean()).isTrue();
    }
}
