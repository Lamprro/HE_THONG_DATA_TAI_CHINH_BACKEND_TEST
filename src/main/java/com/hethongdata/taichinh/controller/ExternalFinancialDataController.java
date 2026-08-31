package com.hethongdata.taichinh.controller;

import com.hethongdata.taichinh.application.port.ExternalFinancialDataPort;
import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalFetchResponse;
import com.hethongdata.taichinh.application.port.model.ExternalOperation;
import com.hethongdata.taichinh.common.AppParams;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
                ExternalOperation.HEALTH, null, null, null, null, null, Map.of()));
    }

    @GetMapping("/providers")
    public ResponseEntity<String> providers() {
        return passthrough(new ExternalFetchRequest(
                ExternalOperation.PROVIDERS, null, null, null, null, null, Map.of()));
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

    @GetMapping("/fetch")
    public ResponseEntity<String> fetch(
            @RequestParam ExternalOperation operation,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) LocalDate start,
            @RequestParam(required = false) LocalDate end,
            @RequestParam(required = false) String interval,
            @RequestParam Map<String, String> parameters) {
        Map<String, String> providerParameters = parameters.entrySet().stream()
                .filter(entry -> !AppParams.EXTERNAL_FRAMEWORK_QUERY_PARAMS.contains(entry.getKey()))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        return passthrough(new ExternalFetchRequest(
                operation, provider, symbol, start, end, interval, providerParameters));
    }

    /** The adapter owns provider URLs; this controller only writes the upstream response to HTTP. */
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

}
