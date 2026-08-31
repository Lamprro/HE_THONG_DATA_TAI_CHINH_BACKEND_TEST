package com.hethongdata.taichinh.controller.exception;

import com.hethongdata.taichinh.application.port.error.ExternalFetchException;
import com.hethongdata.taichinh.dto.ApiErrorResponse;
import com.hethongdata.taichinh.service.ingestion.IngestionExecutionException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The sole HTTP exception boundary for controllers. Services throw meaningful
 * exceptions; this class converts them to one stable API error contract.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream().findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Request is invalid");
        return error(HttpStatus.BAD_REQUEST, "VALIDATION", null, null, message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> invalidRequest(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION", null, null, exception.getMessage());
    }

    @ExceptionHandler(IngestionExecutionException.class)
    ResponseEntity<ApiErrorResponse> ingestionFailure(IngestionExecutionException exception) {
        return error(resolveUpstreamStatus(exception.upstreamStatus()), exception.category().name(), exception.runId(),
                exception.upstreamStatus(), exception.getMessage());
    }

    @ExceptionHandler(ExternalFetchException.class)
    ResponseEntity<ApiErrorResponse> externalFailure(ExternalFetchException exception) {
        return error(resolveUpstreamStatus(exception.upstreamStatus()), exception.category().name(), null,
                exception.upstreamStatus(), exception.getMessage());
    }

    /** Never expose a successful or unknown upstream code as an error response. */
    private static HttpStatus resolveUpstreamStatus(Integer upstreamStatus) {
        HttpStatus status = upstreamStatus == null ? null : HttpStatus.resolve(upstreamStatus);
        return status == null || status.is2xxSuccessful() ? HttpStatus.BAD_GATEWAY : status;
    }

    private static ResponseEntity<ApiErrorResponse> error(
            HttpStatus status, String category, UUID runId, Integer upstreamStatus, String message) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(Instant.now(), category, runId, upstreamStatus, message));
    }
}
