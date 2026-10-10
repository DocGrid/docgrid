package com.opensource.docgrid.domain.failover.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.opensource.docgrid.global.common.response.ErrorResponse;
import com.opensource.docgrid.global.diagnostics.HaProbeDiagnosticContext;
import com.opensource.docgrid.global.exception.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Converts test-only HA write exceptions without persisting raw JDBC messages or infrastructure IDs.
 *
 * <p>It applies only to the profile-gated probe controller. Production endpoints keep their existing
 * global exception handling and this advice never retries a write with an unknown commit result.
 */
@Slf4j
@Profile("ha-probe")
@ConditionalOnProperty(name = "docgrid.ha-probe.enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {HaProbeWriteController.class, HaProbeIdempotentWriteController.class})
public class HaProbeDiagnosticAdvice {

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleConflict(
        DataIntegrityViolationException failure, HttpServletRequest request
    ) {
        // 1. Preserve the probe's previous 409 contract for a duplicate physical write.
        logFailure(failure, HttpStatus.CONFLICT);
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of(ErrorCode.DATA_CONFLICT, "데이터 무결성 오류가 발생했습니다.", request));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleFailure(
        RuntimeException failure, HttpServletRequest request
    ) {
        // 2. Return the existing generic 500 body without copying JDBC text into this diagnostic event.
        logFailure(failure, HttpStatus.INTERNAL_SERVER_ERROR);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR, request));
    }

    private void logFailure(RuntimeException failure, HttpStatus status) {
        log.error("HA_PROBE_EXCEPTION run={} request={} phase={} type={} sqlstate={} status={}",
            HaProbeDiagnosticContext.runId(), HaProbeDiagnosticContext.requestId(),
            HaProbeDiagnosticContext.phase(), failure.getClass().getSimpleName(),
            HaProbeDiagnosticContext.sqlState(failure), status.value());
    }
}
