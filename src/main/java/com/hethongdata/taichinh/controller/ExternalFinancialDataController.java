package com.hethongdata.taichinh.controller;

import com.hethongdata.taichinh.application.port.ExternalFinancialDataPort;
import com.hethongdata.taichinh.application.port.error.ExternalFetchException;
import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalFetchResponse;
import com.hethongdata.taichinh.application.port.model.ExternalOperation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/external-financial-data")
public class ExternalFinancialDataController {

    private final ExternalFinancialDataPort externalFinancialDataPort;

    public ExternalFinancialDataController(ExternalFinancialDataPort externalFinancialDataPort) {
        this.externalFinancialDataPort = externalFinancialDataPort;
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return passthrough(new ExternalFetchRequest(
                ExternalOperation.HEALTH, "vnstock", null, null, null, null, Map.of()));
    }

    @GetMapping("/providers")
    public ResponseEntity<String> providers() {
        return passthrough(new ExternalFetchRequest(
                ExternalOperation.PROVIDERS, "vnstock", null, null, null, null, Map.of()));
    }

    @GetMapping("/{provider}/equities/{symbol}/quote")
    public ResponseEntity<String> quote(@PathVariable String provider, @PathVariable String symbol) {
        return passthrough(new ExternalFetchRequest(
                ExternalOperation.QUOTE, provider, symbol, null, null, null, Map.of()));
    }

    @GetMapping("/{provider}/equities/{symbol}/ohlcv")
    public ResponseEntity<String> ohlcv(
            @PathVariable String provider,
            @PathVariable String symbol,
            @RequestParam(required = false) LocalDate start,
            @RequestParam(required = false) LocalDate end) {
        return passthrough(new ExternalFetchRequest(
                ExternalOperation.OHLCV, provider, symbol, start, end, null, Map.of()));
    }

    private ResponseEntity<String> passthrough(ExternalFetchRequest request) {
        ExternalFetchResponse response = externalFinancialDataPort.fetch(request);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(response.contentType());
        } catch (IllegalArgumentException exception) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.status(response.httpStatus())
                .header(HttpHeaders.CONTENT_TYPE, mediaType.toString())
                .body(response.rawBody());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<GatewayError> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new GatewayError(
                Instant.now(), "VALIDATION", null, exception.getMessage()));
    }

    @ExceptionHandler(ExternalFetchException.class)
    public ResponseEntity<GatewayError> externalError(ExternalFetchException exception) {
        HttpStatus status = exception.upstreamStatus() == null
                ? HttpStatus.BAD_GATEWAY
                : HttpStatus.resolve(exception.upstreamStatus());
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        return ResponseEntity.status(status).body(new GatewayError(
                Instant.now(), exception.category().name(), exception.upstreamStatus(), exception.getMessage()));
    }

    public record GatewayError(
            Instant timestamp,
            String category,
            Integer upstreamStatus,
            String message) {
    }
}
