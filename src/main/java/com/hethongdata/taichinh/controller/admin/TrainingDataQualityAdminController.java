package com.hethongdata.taichinh.controller.admin;
import com.hethongdata.taichinh.service.market.TrainingDataQualityService;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/financial-data/quality")
public class TrainingDataQualityAdminController {
    private final TrainingDataQualityService service;private final String token;
    public TrainingDataQualityAdminController(TrainingDataQualityService service,@Value("${financial.admin.api-token:}") String token) {
        this.service=service;this.token=token;
    }
    private void authorize(String value) {
        if(token.length()<32) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Admin token not configured");
        if(value==null || !value.startsWith("Bearer ") || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),value.substring(7).getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Admin credential required");
    }
    @GetMapping("/coverage") public Map<String,Object> coverage(@RequestHeader(value="Authorization",required=false) String auth) {authorize(auth);return service.coverage();}
    @PostMapping("/review-legacy/{symbol}") public Map<String,Object> review(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable String symbol) throws Exception {
        authorize(auth);return service.reviewLegacy(symbol);
    }
}
