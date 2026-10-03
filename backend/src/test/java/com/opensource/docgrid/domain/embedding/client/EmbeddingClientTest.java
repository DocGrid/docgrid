package com.opensource.docgrid.domain.embedding.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatus;

import com.opensource.docgrid.domain.embedding.dto.response.EmbedServerResponse;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedBatchItemResponse;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedBatchServerResponse;
import com.opensource.docgrid.domain.embedding.client.EmbeddingProviderCircuitBreaker.CallPermission;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * EmbeddingClient의 HTTP 응답 전달, 실패 분류와 Circuit Callback 경계를 검증한다.
 *
 * <p>Circuit 자체 상태 전이와 실제 네트워크 I/O는 별도 단위 테스트의 범위다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EmbeddingClient 단위 테스트")
class EmbeddingClientTest {

    @Mock private RestClient queryRestClient;
    @Mock private RestClient documentRestClient;
    @Mock(answer = Answers.RETURNS_SELF) private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private RestClient.ResponseSpec responseSpec;
    @Mock private EmbeddingProviderCircuitBreaker circuitBreaker;

    private EmbeddingClient embeddingClient;
    private CallPermission permission;

    @BeforeEach
    void setUp() {
        permission = new CallPermission(1L, false, false);
        given(circuitBreaker.acquirePermission()).willReturn(permission);
        embeddingClient = new EmbeddingClient(
            queryRestClient,
            documentRestClient,
            circuitBreaker
        );
        doReturn(requestBodyUriSpec).when(queryRestClient).post();
        doReturn(requestBodyUriSpec).when(documentRestClient).post();
        doReturn(responseSpec).when(requestBodyUriSpec).retrieve();
    }

    @Test
    @DisplayName("정상 응답: 외부 서버의 Vector를 그대로 반환한다")
    void embed_success() {
        float[] vector = new float[]{0.1f, 0.2f};
        given(responseSpec.body(EmbedServerResponse.class))
            .willReturn(new EmbedServerResponse(vector));

        float[] result = embeddingClient.embed("검색어");

        assertThat(result).containsExactly(vector);
        verify(queryRestClient).post();
        verify(requestBodyUriSpec).contentType(MediaType.APPLICATION_JSON);
        verify(documentRestClient, never()).post();
        verify(circuitBreaker).recordSuccess(permission);
    }

    @Test
    @DisplayName("빈 응답: 외부 서버 응답이 null이면 null을 반환한다")
    void embed_returnsNull_whenResponseIsNull() {
        given(responseSpec.body(EmbedServerResponse.class)).willReturn(null);

        float[] result = embeddingClient.embed("검색어");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("서버 장애: RestClientException을 서비스 사용 불가 오류로 변환한다")
    void embed_throws_whenServerUnavailable() {
        given(responseSpec.body(EmbedServerResponse.class))
            .willThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> embeddingClient.embed("검색어"))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_SERVER_UNAVAILABLE);
        verify(circuitBreaker).recordFailure(permission, true);
    }

    @Test
    @DisplayName("예상 밖 예외: 결과를 기록하지 못하면 Circuit Permission을 반환한다")
    void embed_releasesPermission_whenUnexpectedFailureOccurs() {
        given(responseSpec.body(EmbedServerResponse.class))
            .willThrow(new IllegalStateException("unexpected response failure"));

        assertThatThrownBy(() -> embeddingClient.embed("검색어"))
            .isInstanceOf(IllegalStateException.class);
        verify(circuitBreaker).releasePermission(permission);
    }

    @Test
    @DisplayName("서버 timeout: 응답 제한 초과를 별도 Retry 오류로 분류한다")
    void embed_mapsTimeoutToDedicatedFailure() {
        given(responseSpec.body(EmbedServerResponse.class)).willThrow(
            new ResourceAccessException("timeout", new HttpTimeoutException("read timeout"))
        );

        assertThatThrownBy(() -> embeddingClient.embed("검색어"))
            .isInstanceOf(EmbeddingProviderException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_PROVIDER_TIMEOUT);
        verify(circuitBreaker).recordFailure(permission, true);
    }

    @Test
    @DisplayName("HTTP 408: Provider timeout으로 분류해 Job 재시도를 허용한다")
    void embed_mapsRequestTimeoutResponseToDedicatedFailure() {
        given(responseSpec.body(EmbedServerResponse.class))
            .willThrow(new HttpClientErrorException(HttpStatus.REQUEST_TIMEOUT));

        assertThatThrownBy(() -> embeddingClient.embed("검색어"))
            .isInstanceOf(EmbeddingProviderException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_PROVIDER_TIMEOUT);
        verify(circuitBreaker).recordFailure(permission, true);
    }

    @Test
    @DisplayName("단건 과부하: HTTP 429를 Provider 과부하 오류로 분리한다")
    void embed_mapsTooManyRequestsToOverload() {
        given(responseSpec.body(EmbedServerResponse.class))
            .willThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> embeddingClient.embed("검색어"))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_PROVIDER_OVERLOADED);
    }

    @Test
    @DisplayName("단건 과부하: Retry-After delta-seconds를 Job 최소 지연으로 보존한다")
    void embed_preservesRetryAfterForOverload() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "15");
        given(responseSpec.body(EmbedServerResponse.class)).willThrow(
            HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                headers,
                new byte[0],
                StandardCharsets.UTF_8
            )
        );

        assertThatThrownBy(() -> embeddingClient.embed("검색어"))
            .isInstanceOf(EmbeddingProviderException.class)
            .hasFieldOrPropertyWithValue("minimumRetryDelay", Duration.ofSeconds(15));
        verify(circuitBreaker).recordFailure(permission, true);
    }

    @Test
    @DisplayName("Circuit 개방 실패: Retry-After보다 긴 Open 시간을 Job 최소 지연으로 사용한다")
    void embed_prefersCircuitOpenDelay() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "15");
        given(responseSpec.body(EmbedServerResponse.class)).willThrow(
            HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                headers,
                new byte[0],
                StandardCharsets.UTF_8
            )
        );
        given(circuitBreaker.recordFailure(permission, true))
            .willReturn(Duration.ofSeconds(30));

        assertThatThrownBy(() -> embeddingClient.embed("검색어"))
            .isInstanceOf(EmbeddingProviderException.class)
            .hasFieldOrPropertyWithValue("minimumRetryDelay", Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("영구 4xx: 요청 계약 오류로 분류하고 Circuit 실패에 포함하지 않는다")
    void embed_mapsClientErrorToPermanentFailure() {
        given(responseSpec.body(EmbedServerResponse.class))
            .willThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> embeddingClient.embed("검색어"))
            .isInstanceOf(EmbeddingProviderException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_REQUEST_REJECTED);
        assertThat(ErrorCode.EMBEDDING_REQUEST_REJECTED.getHttpStatus())
            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        verify(circuitBreaker).recordFailure(permission, false);
    }

    @Test
    @DisplayName("Circuit Open: 실제 HTTP 호출 전에 빠르게 실패한다")
    void embed_failsFastWhenCircuitIsOpen() {
        given(circuitBreaker.acquirePermission()).willThrow(
            new EmbeddingProviderException(
                ErrorCode.EMBEDDING_PROVIDER_CIRCUIT_OPEN,
                Duration.ofSeconds(30),
                false
            )
        );

        assertThatThrownBy(() -> embeddingClient.embed("검색어"))
            .isInstanceOf(EmbeddingProviderException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.EMBEDDING_PROVIDER_CIRCUIT_OPEN
            );
        verify(queryRestClient, never()).post();
    }

    @Test
    @DisplayName("Batch 정상 응답: 모델과 요청 순서가 검증된 결과를 반환한다")
    void embedBatch_returnsValidatedResponse() {
        EmbedBatchServerResponse response = new EmbedBatchServerResponse(
            "BAAI/bge-m3",
            List.of(
                new EmbedBatchItemResponse(0, new float[]{0.1f, 0.2f}),
                new EmbedBatchItemResponse(1, new float[]{0.3f, 0.4f})
            )
        );
        given(responseSpec.body(EmbedBatchServerResponse.class)).willReturn(response);

        EmbedBatchServerResponse result = embeddingClient.embedBatch(List.of("첫 번째", "두 번째"), 16);

        assertThat(result.model()).isEqualTo("BAAI/bge-m3");
        assertThat(result.embeddings())
            .extracting(EmbedBatchItemResponse::index)
            .containsExactly(0, 1);
        verify(documentRestClient).post();
        verify(requestBodyUriSpec).contentType(MediaType.APPLICATION_JSON);
        verify(queryRestClient, never()).post();
    }

    @Test
    @DisplayName("Batch 예상 밖 예외: 결과를 기록하지 못하면 Circuit Permission을 반환한다")
    void embedBatch_releasesPermission_whenUnexpectedFailureOccurs() {
        given(responseSpec.body(EmbedBatchServerResponse.class))
            .willThrow(new IllegalStateException("unexpected response failure"));

        assertThatThrownBy(() -> embeddingClient.embedBatch(List.of("본문"), 16))
            .isInstanceOf(IllegalStateException.class);
        verify(circuitBreaker).releasePermission(permission);
    }

    @Test
    @DisplayName("Batch 빈 응답: 외부 서버 응답이 null이면 정합성 오류로 거부한다")
    void embedBatch_rejectsNullResponse() {
        given(responseSpec.body(EmbedBatchServerResponse.class)).willReturn(null);

        assertThatThrownBy(() -> embeddingClient.embedBatch(List.of("본문"), 16))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
    }

    @Test
    @DisplayName("Batch 모델 누락: 빈 모델 식별자는 정합성 오류로 거부한다")
    void embedBatch_rejectsBlankModel() {
        given(responseSpec.body(EmbedBatchServerResponse.class))
            .willReturn(new EmbedBatchServerResponse(
                " ",
                List.of(new EmbedBatchItemResponse(0, new float[]{0.1f}))
            ));

        assertThatThrownBy(() -> embeddingClient.embedBatch(List.of("본문"), 16))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
    }

    @Test
    @DisplayName("Batch 개수 불일치: 요청과 다른 결과 개수는 정합성 오류로 거부한다")
    void embedBatch_rejectsCountMismatch() {
        given(responseSpec.body(EmbedBatchServerResponse.class))
            .willReturn(new EmbedBatchServerResponse("BAAI/bge-m3", List.of()));

        assertThatThrownBy(() -> embeddingClient.embedBatch(List.of("본문"), 16))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
    }

    @Test
    @DisplayName("Batch 순서 불일치: 요청 위치와 다른 Index는 정합성 오류로 거부한다")
    void embedBatch_rejectsIndexMismatch() {
        given(responseSpec.body(EmbedBatchServerResponse.class))
            .willReturn(new EmbedBatchServerResponse(
                "BAAI/bge-m3",
                List.of(new EmbedBatchItemResponse(1, new float[]{0.1f}))
            ));

        assertThatThrownBy(() -> embeddingClient.embedBatch(List.of("본문"), 16))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
    }

    @Test
    @DisplayName("Batch 서버 장애: RestClientException을 서비스 사용 불가 오류로 변환한다")
    void embedBatch_throwsWhenServerUnavailable() {
        given(responseSpec.body(EmbedBatchServerResponse.class))
            .willThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> embeddingClient.embedBatch(List.of("본문"), 16))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_SERVER_UNAVAILABLE);
    }

    @Test
    @DisplayName("Batch 과부하: HTTP 429를 Provider 과부하 오류로 분리한다")
    void embedBatch_mapsTooManyRequestsToOverload() {
        given(responseSpec.body(EmbedBatchServerResponse.class))
            .willThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> embeddingClient.embedBatch(List.of("본문"), 1))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_PROVIDER_OVERLOADED);
    }
}
