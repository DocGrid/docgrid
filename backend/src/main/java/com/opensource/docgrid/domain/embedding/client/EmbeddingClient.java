package com.opensource.docgrid.domain.embedding.client;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.opensource.docgrid.domain.embedding.dto.request.EmbedBatchRequest;
import com.opensource.docgrid.domain.embedding.dto.request.EmbedRequest;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedBatchItemResponse;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedBatchServerResponse;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedServerResponse;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 외부 Embedding Server의 단건·Batch Vector 생성 HTTP 계약을 담당한다.
 *
 * <p>검색 단건과 문서 Batch는 같은 Provider를 사용하지만 응답 시간 예산이 다르므로 전용 HTTP Client를
 * 분리한다. 모델 선택과 Vector 차원 검증은 호출 Service가 담당하며, 이 클래스는 요청 계약과 전송 오류
 * 변환 경계만 책임진다. 공유 Circuit은 연속 가용성 실패를 빠르게 차단하되 HTTP 호출 내부 재시도는
 * 수행하지 않는다.
 */
@Slf4j
@Component
public class EmbeddingClient {

    private final RestClient queryRestClient;
    private final RestClient documentRestClient;
    private final EmbeddingProviderCircuitBreaker circuitBreaker;

    /**
     * 짧은 검색 Query와 긴 문서 Batch에 서로 다른 Timeout 정책의 HTTP Client를 연결한다.
     */
    public EmbeddingClient(
        @Qualifier("embeddingRestClient") RestClient queryRestClient,
        @Qualifier("documentEmbeddingRestClient") RestClient documentRestClient,
        EmbeddingProviderCircuitBreaker circuitBreaker
    ) {
        this.queryRestClient = queryRestClient;
        this.documentRestClient = documentRestClient;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * 입력 Text를 외부 서버에 전달하고 Dense Vector를 반환한다.
     */
    public float[] embed(String text) {
        // 1. 공유 Circuit에서 현재 세대의 호출 또는 Half-open Probe 권한을 획득한다.
        EmbeddingProviderCircuitBreaker.CallPermission permission =
            circuitBreaker.acquirePermission();
        EmbedServerResponse response;
        boolean resultRecorded = false;

        // 2. 검색 Query 전용 시간 예산으로 단건 Embedding 요청을 한 번 수행한다.
        try {
            response = queryRestClient.post()
                .uri("/embed")
                // JSON을 명시해 XML Converter가 Provider 요청 DTO를 먼저 선택하지 못하게 한다.
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmbedRequest(text))
                .retrieve()
                .body(EmbedServerResponse.class);
            circuitBreaker.recordSuccess(permission);
            resultRecorded = true;
        } catch (RestClientException exception) {
            // 3. 전송 실패를 공개 오류 정책으로 변환하고 Circuit 상태 및 최소 Retry 지연을 함께 갱신한다.
            EmbeddingProviderException providerException = translateFailure("단건", exception);
            Duration circuitDelay = circuitBreaker.recordFailure(
                permission,
                providerException.isCircuitFailure()
            );
            resultRecorded = true;
            throw providerException.withMinimumRetryDelay(circuitDelay);
        } finally {
            // 4. 예상 밖 예외가 결과 기록을 건너뛰어도 Half-open Probe 소유권은 반드시 반환한다.
            if (!resultRecorded) {
                circuitBreaker.releasePermission(permission);
            }
        }

        // 5. HTTP 성공이지만 빈 Body인 경우까지 호출 Service가 계약 오류로 판정할 수 있도록 null을 보존한다.
        return response == null ? null : response.vector();
    }

    /**
     * 정렬된 Text 목록을 Batch API로 전달하고 모델·개수·순서 계약을 검증한다.
     */
    public EmbedBatchServerResponse embedBatch(List<String> texts, int batchSize) {
        // 1. 단건 호출과 공유하는 Circuit에서 현재 호출 권한을 획득한다.
        EmbeddingProviderCircuitBreaker.CallPermission permission =
            circuitBreaker.acquirePermission();
        EmbedBatchServerResponse response;
        boolean resultRecorded = false;

        // 2. 문서 처리 전용 시간 예산으로 정렬된 Text Batch를 한 번 전송한다.
        try {
            response = documentRestClient.post()
                .uri("/embed/batch")
                // FastAPI Batch 계약은 JSON 객체이므로 classpath의 XML 지원 여부와 무관하게 고정한다.
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmbedBatchRequest(texts, batchSize))
                .retrieve()
                .body(EmbedBatchServerResponse.class);
            circuitBreaker.recordSuccess(permission);
            resultRecorded = true;
        } catch (RestClientException exception) {
            // 3. Provider 실패를 Retry·Circuit 정책 입력으로 변환하고 안전한 최소 지연을 보존한다.
            EmbeddingProviderException providerException = translateFailure("Batch", exception);
            Duration circuitDelay = circuitBreaker.recordFailure(
                permission,
                providerException.isCircuitFailure()
            );
            resultRecorded = true;
            throw providerException.withMinimumRetryDelay(circuitDelay);
        } finally {
            // 4. 예상 밖 예외가 결과 기록을 건너뛰어도 Half-open Probe 소유권은 반드시 반환한다.
            if (!resultRecorded) {
                circuitBreaker.releasePermission(permission);
            }
        }

        // 5. 저장 단계 전에 응답 모델 정보와 입력 대비 개수·순서 계약을 검증한다.
        validateBatchResponse(response, texts == null ? -1 : texts.size());
        return response;
    }

    /**
     * HTTP Client 예외를 인덱싱 Retry와 Circuit이 공유하는 제한된 Provider 예외로 변환한다.
     */
    private EmbeddingProviderException translateFailure(
        String operation,
        RestClientException exception
    ) {
        // 1. timeout, 과부하, 서버 장애와 영구 4xx를 분리해 Job Retry와 Circuit의 입력을 고정한다.
        ErrorCode errorCode = resolveErrorCode(exception);
        Duration minimumRetryDelay = errorCode == ErrorCode.EMBEDDING_PROVIDER_OVERLOADED
            ? retryAfter(exception)
            : Duration.ZERO;
        boolean circuitFailure = errorCode == ErrorCode.EMBEDDING_PROVIDER_TIMEOUT
            || errorCode == ErrorCode.EMBEDDING_PROVIDER_OVERLOADED
            || errorCode == ErrorCode.EMBEDDING_SERVER_UNAVAILABLE;

        // 2. 외부 응답 본문은 문서 내용이나 내부 정보를 포함할 수 있어 오류 유형만 기록한다.
        log.error(
            "임베딩 서버 {} 호출에 실패했습니다. errorCode={}, cause={}",
            operation,
            errorCode.getCode(),
            exception.getClass().getSimpleName()
        );
        return new EmbeddingProviderException(
            errorCode,
            minimumRetryDelay,
            circuitFailure,
            exception
        );
    }

    /**
     * 전송 예외와 HTTP 상태를 Timeout, 과부하, 영구 요청 오류 또는 서버 비가용으로 분류한다.
     */
    private ErrorCode resolveErrorCode(RestClientException exception) {
        if (isTimeout(exception)) {
            return ErrorCode.EMBEDDING_PROVIDER_TIMEOUT;
        }
        if (exception instanceof RestClientResponseException responseException) {
            if (responseException.getStatusCode().value() == 408) {
                return ErrorCode.EMBEDDING_PROVIDER_TIMEOUT;
            }
            if (responseException.getStatusCode().value() == 429) {
                return ErrorCode.EMBEDDING_PROVIDER_OVERLOADED;
            }
            if (responseException.getStatusCode().is4xxClientError()) {
                return ErrorCode.EMBEDDING_REQUEST_REJECTED;
            }
        }
        return ErrorCode.EMBEDDING_SERVER_UNAVAILABLE;
    }

    /**
     * Resource 접근 예외의 원인 체인에 알려진 네트워크 Timeout이 포함됐는지 확인한다.
     */
    private boolean isTimeout(RestClientException exception) {
        if (!(exception instanceof ResourceAccessException)) {
            return false;
        }
        Throwable current = exception;
        while (current != null) {
            if (current instanceof HttpTimeoutException
                || current instanceof SocketTimeoutException
                || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 과부하 응답의 초 단위 Retry-After Header를 안전한 최소 재시도 지연으로 해석한다.
     *
     * <p>HTTP-date 형식, 음수 또는 잘못된 값은 서버가 강제한 지연이 없는 것으로 처리한다.
     */
    private Duration retryAfter(RestClientException exception) {
        if (!(exception instanceof RestClientResponseException responseException)) {
            return Duration.ZERO;
        }
        HttpHeaders headers = responseException.getResponseHeaders();
        String rawValue = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (!StringUtils.hasText(rawValue)) {
            return Duration.ZERO;
        }
        try {
            long seconds = Long.parseLong(rawValue.trim());
            return seconds < 0 ? Duration.ZERO : Duration.ofSeconds(seconds);
        } catch (NumberFormatException exceptionCause) {
            return Duration.ZERO;
        }
    }

    /**
     * Batch 응답에 모델 이름이 있고 각 결과가 입력 개수와 0부터 시작하는 순서를 정확히 따르는지 검증한다.
     */
    private void validateBatchResponse(EmbedBatchServerResponse response, int expectedCount) {
        if (response == null
            || !StringUtils.hasText(response.model())
            || response.embeddings() == null
            || response.embeddings().size() != expectedCount) {
            throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
        }

        for (int index = 0; index < response.embeddings().size(); index++) {
            EmbedBatchItemResponse item = response.embeddings().get(index);
            if (item == null || item.index() != index) {
                throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
            }
        }
    }
}
