package com.hethongdata.taichinh.controller.exception;

import com.hethongdata.taichinh.application.port.error.ExternalFetchException;
import com.hethongdata.taichinh.application.port.error.ExternalErrorCategory;
import com.hethongdata.taichinh.dto.ApiErrorResponse;
import com.hethongdata.taichinh.service.ingestion.IngestionExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

/**
 * The sole HTTP exception boundary for controllers. Services throw meaningful exceptions; this
 * class converts them to one stable API error contract and logs diagnostics for dev/operations.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException exception) {
        String message =
                exception.getBindingResult().getFieldErrors().stream()
                        .findFirst()
                        .map(error -> error.getField() + " " + error.getDefaultMessage())
                        .orElse("Yêu cầu không hợp lệ.");
        LOGGER.warn("Validation error on request: {}", message);
        return error(HttpStatus.BAD_REQUEST, "VALIDATION", null, null, message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> invalidRequest(IllegalArgumentException exception) {
        LOGGER.warn("Invalid request argument: {}", exception.getMessage());
        return error(
                HttpStatus.BAD_REQUEST,
                "VALIDATION",
                null,
                null,
                exception.getMessage() == null ? "Yêu cầu không hợp lệ." : exception.getMessage());
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> unsupportedMethod(org.springframework.web.HttpRequestMethodNotSupportedException exception) {
        var response=error(HttpStatus.METHOD_NOT_ALLOWED,"METHOD_NOT_ALLOWED",null,null,"This endpoint does not support that HTTP method");
        var headers=new org.springframework.http.HttpHeaders();
        if(exception.getSupportedHttpMethods()!=null) headers.setAllow(exception.getSupportedHttpMethods());
        return new ResponseEntity<>(response.getBody(),headers,HttpStatus.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> invalidParameter(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException exception) {
        String type=exception.getRequiredType()==null?"supported value":exception.getRequiredType().getSimpleName();
        return error(HttpStatus.BAD_REQUEST,"VALIDATION",null,null,"Invalid parameter '"+exception.getName()+"': expected "+type);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> unreadableBody(org.springframework.http.converter.HttpMessageNotReadableException exception) {
        return error(HttpStatus.BAD_REQUEST,"VALIDATION",null,null,"Request JSON is malformed or a field has an invalid type/date/enum value");
    }

    @ExceptionHandler(IngestionExecutionException.class)
    ResponseEntity<ApiErrorResponse> ingestionFailure(IngestionExecutionException exception) {
        LOGGER.error(
                "Ingestion execution failure: runId={}, category={}, upstreamStatus={}, message={}",
                exception.runId(),
                exception.category(),
                exception.upstreamStatus(),
                exception.getMessage(),
                exception);
        return error(
                resolveUpstreamStatus(exception.upstreamStatus(), exception.category()),
                exception.category().name(),
                exception.runId(),
                exception.upstreamStatus(),
                exception.getMessage());
    }

    @ExceptionHandler(ExternalFetchException.class)
    ResponseEntity<ApiErrorResponse> externalFailure(ExternalFetchException exception) {
        LOGGER.error(
                "External fetch failure: category={}, upstreamStatus={}, message={}",
                exception.category(),
                exception.upstreamStatus(),
                exception.getMessage(),
                exception);
        return error(
                resolveUpstreamStatus(exception.upstreamStatus(), exception.category()),
                exception.category().name(),
                null,
                exception.upstreamStatus(),
                exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> unhandledFailure(Exception exception) {
        LOGGER.error("Unhandled internal server exception: {}", exception.getMessage(), exception);
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                null,
                null,
                "Hệ thống đang gặp vấn đề. Vui lòng thử lại sau.");
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<ApiErrorResponse> explicitStatus(org.springframework.web.server.ResponseStatusException exception) {
        return error(HttpStatus.valueOf(exception.getStatusCode().value()), "REQUEST_STATE", null, null,
                exception.getReason() == null ? "Request state conflict" : exception.getReason());
    }

    /** Never expose a successful or unknown upstream code as an error response. */
    private static HttpStatus resolveUpstreamStatus(
            Integer upstreamStatus, ExternalErrorCategory category) {
        HttpStatus status = upstreamStatus == null ? null : HttpStatus.resolve(upstreamStatus);
        if (category == ExternalErrorCategory.RATE_LIMIT
                && status == HttpStatus.TOO_MANY_REQUESTS) {
            return status;
        }
        return HttpStatus.BAD_GATEWAY;
    }

    private static ResponseEntity<ApiErrorResponse> error(
            HttpStatus status,
            String category,
            UUID runId,
            Integer upstreamStatus,
            String message) {
        return ResponseEntity.status(status)
                .body(
                        new ApiErrorResponse(
                                Instant.now(), category, runId, upstreamStatus, message));
    }
}
