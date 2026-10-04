package com.opensource.docgrid.domain.embedding.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.embedding.config.EmbeddingProviderCircuitBreakerProperties;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedBatchServerResponse;
import com.opensource.docgrid.global.config.EmbeddingServerConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 운영 설정({@link EmbeddingServerConfig})으로 만든 실제 RestClient가 Embedding Server에 보내는 요청의 전송 형식을 검증한다.
 *
 * <p>기존 {@code EmbeddingClientTest}는 RestClient를 mock으로 대체해 실제 Content-Type과 본문을 보지 못한다.
 * {@code jackson-dataformat-xml}이 Classpath에 있으면 Content-Type을 지정하지 않은 요청이 XML로 나가 FastAPI가
 * 422로 거부하는데, 이 테스트는 로컬 HTTP 서버가 받은 실제 요청으로 그 회귀를 잡는다.
 */
@DisplayName("EmbeddingClient 전송 계약 테스트")
class EmbeddingClientWireContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private EmbeddingClient embeddingClient;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embed/batch", exchange -> respond(exchange,
            "{\"model\":\"BAAI/bge-m3\",\"embeddings\":[{\"index\":0,\"vector\":[0.1,0.2]},"
                + "{\"index\":1,\"vector\":[0.3,0.4]}]}"));
        server.createContext("/embed", exchange -> respond(exchange, "{\"vector\":[0.1,0.2]}"));
        server.start();

        EmbeddingServerConfig config = new EmbeddingServerConfig();
        ReflectionTestUtils.setField(config, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(config, "connectTimeout", Duration.ofSeconds(2));
        ReflectionTestUtils.setField(config, "readTimeout", Duration.ofSeconds(5));
        ReflectionTestUtils.setField(config, "documentReadTimeout", Duration.ofSeconds(5));

        EmbeddingProviderCircuitBreakerProperties circuitProperties = new EmbeddingProviderCircuitBreakerProperties();
        circuitProperties.setEnabled(false);
        embeddingClient = new EmbeddingClient(
            config.embeddingRestClient(),
            config.documentEmbeddingRestClient(),
            new EmbeddingProviderCircuitBreaker(
                circuitProperties,
                Clock.systemUTC(),
                new EmbeddingProviderCircuitMetrics(new SimpleMeterRegistry())
            )
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("단건 Embedding 요청은 JSON으로 전송한다")
    void embed_sendsJsonRequest() throws IOException {
        float[] vector = embeddingClient.embed("검색어");

        assertThat(vector).containsExactly(0.1f, 0.2f);
        ReceivedRequest request = onlyRequest("/embed");
        assertThat(request.contentType()).startsWith("application/json");
        assertThat(objectMapper.readTree(request.body()).get("text").asText()).isEqualTo("검색어");
    }

    @Test
    @DisplayName("Batch Embedding 요청은 JSON으로 전송한다")
    void embedBatch_sendsJsonRequest() throws IOException {
        EmbedBatchServerResponse response = embeddingClient.embedBatch(List.of("첫 번째", "두 번째"), 2);

        assertThat(response.model()).isEqualTo("BAAI/bge-m3");
        ReceivedRequest request = onlyRequest("/embed/batch");
        assertThat(request.contentType()).startsWith("application/json");
        JsonNode body = objectMapper.readTree(request.body());
        assertThat(body.get("texts")).hasSize(2);
        assertThat(body.get("texts").get(0).asText()).isEqualTo("첫 번째");
        assertThat(body.get("batch_size").asInt()).isEqualTo(2);
    }

    private ReceivedRequest onlyRequest(String path) {
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).path()).isEqualTo(path);
        return requests.get(0);
    }

    private void respond(HttpExchange exchange, String json) throws IOException {
        requests.add(new ReceivedRequest(
            exchange.getRequestURI().getPath(),
            exchange.getRequestHeaders().getFirst("Content-Type"),
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
        ));
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private record ReceivedRequest(String path, String contentType, String body) {
    }
}
