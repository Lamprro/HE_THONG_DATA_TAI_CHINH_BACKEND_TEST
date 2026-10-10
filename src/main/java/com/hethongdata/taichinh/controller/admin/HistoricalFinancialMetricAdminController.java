package com.hethongdata.taichinh.controller.admin;

import com.hethongdata.taichinh.service.financial.HistoricalFinancialMetricService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/financial-data/metrics")
public class HistoricalFinancialMetricAdminController {
    private final HistoricalFinancialMetricService service;
    private final String token;
    public HistoricalFinancialMetricAdminController(HistoricalFinancialMetricService service,
            @Value("${financial.admin.api-token:}") String token) {this.service=service;this.token=token;}
    @PostMapping("/recalculate")
    public HistoricalFinancialMetricService.Result calculate(
            @RequestHeader(value="Authorization",required=false) String authorization,
            @RequestBody HistoricalFinancialMetricService.Request request) {
        if (token.length()<32) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Admin token not configured");
        if (authorization==null || !authorization.startsWith("Bearer ") || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),authorization.substring(7).getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Admin credential required");
        return service.calculate(request);
    }
}
