package com.opensource.docgrid.global.exception;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.opensource.docgrid.global.common.response.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

/** API 예외를 공통 오류 응답으로 바꾸고 진행 중인 업로드의 재시도 간격을 알린다. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DocGridException.class)
    public ResponseEntity<ErrorResponse> handleDocGridException(
        DocGridException e, HttpServletRequest request
    ) {
        log.warn("[DocGridException] {} {} | code={} | message={}",
            request.getMethod(), request.getRequestURI(),
            e.getErrorCode().getCode(), e.getMessage());

        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.getErrorCode().getHttpStatus());
        if (e.getErrorCode() == ErrorCode.DOCUMENT_UPLOAD_IN_PROGRESS) {
            response.header(HttpHeaders.RETRY_AFTER, "5");
        }
        return response.body(ErrorResponse.of(e.getErrorCode(), e.getMessage(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
        MethodArgumentNotValidException e, HttpServletRequest request
    ) {
        String detail = e.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .findFirst()
            .orElse(ErrorCode.INVALID_PARAMETER.getMessage());

        log.warn("[Validation] {} {} | {}", request.getMethod(), request.getRequestURI(), detail);

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of(ErrorCode.INVALID_PARAMETER, detail, request));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
        ConstraintViolationException e, HttpServletRequest request
    ) {
        log.warn("[ConstraintViolation] {} {} | {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of(ErrorCode.INVALID_PARAMETER, request));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(
        MissingServletRequestParameterException e, HttpServletRequest request
    ) {
        log.warn("[MissingParam] {} {} | {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of(ErrorCode.INVALID_PARAMETER, request));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
        MethodArgumentTypeMismatchException e, HttpServletRequest request
    ) {
        log.warn("[TypeMismatch] {} {} | {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of(ErrorCode.INVALID_PARAMETER, request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(
        HttpMessageNotReadableException e, HttpServletRequest request
    ) {
        log.warn("[NotReadable] {} {} | {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of(ErrorCode.INVALID_PARAMETER, request));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
        HttpRequestMethodNotSupportedException e, HttpServletRequest request
    ) {
        log.warn("[MethodNotSupported] {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity
            .status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED, request));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(
        HttpMediaTypeNotSupportedException e, HttpServletRequest request
    ) {
        log.warn("[MediaTypeNotSupported] {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity
            .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(ErrorResponse.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE, request));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(
        MaxUploadSizeExceededException e, HttpServletRequest request
    ) {
        log.warn("[MaxUploadSizeExceeded] {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of(ErrorCode.FILE_SIZE_EXCEEDED, request));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(
        AuthenticationException e, HttpServletRequest request
    ) {
        log.warn("[Unauthorized] {} {} | {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse.of(ErrorCode.UNAUTHORIZED, request));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(
        DataIntegrityViolationException e, HttpServletRequest request
    ) {
        log.warn("[DataIntegrity] {} {} | {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of(ErrorCode.DATA_CONFLICT, "데이터 무결성 오류가 발생했습니다.", request));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(
        DataAccessException e, HttpServletRequest request
    ) {
        log.error("[DataAccess] {} {} | {}", request.getMethod(), request.getRequestURI(), e.getMessage(), e);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR, request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(
        Exception e, HttpServletRequest request
    ) {
        log.error("[UnhandledException] {} {} | {}", request.getMethod(), request.getRequestURI(), e.getMessage(), e);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR, request));
    }
}
