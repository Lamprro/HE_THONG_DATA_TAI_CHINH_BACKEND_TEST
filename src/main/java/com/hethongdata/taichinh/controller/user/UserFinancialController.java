package com.hethongdata.taichinh.controller.user;

import com.hethongdata.taichinh.service.user.UserFinancialService;
import com.hethongdata.taichinh.service.user.UserReadSupport.Data;
import com.hethongdata.taichinh.service.user.UserReadSupport.Page;
import com.hethongdata.taichinh.controller.user.UserContractController.EndpointId;
import java.time.LocalDate;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class UserFinancialController {
    private final UserFinancialService service;

    public UserFinancialController(UserFinancialService service) {
        this.service = service;
    }

    @GetMapping("/securities/{symbol}/statements")
    public Page<Map<String,Object>> statements(@PathVariable String symbol,
            @RequestParam(required=false) String exchange,
            @RequestParam(required=false) String type,
            @RequestParam(required=false) String period,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size) {
        return service.statements(symbol,exchange,type,period,from,to,page,size);
    }

    @GetMapping("/securities/{symbol}/statements/{id}")
    public Data<Map<String,Object>> statement(@PathVariable String symbol,
            @PathVariable UUID id,
            @RequestParam(required=false) String exchange) {
        return service.statement(symbol,exchange,id);
    }

    @GetMapping("/metrics")
    public Page<Map<String,Object>> definitions(@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="50") int size) {
        return service.definitions(page,size);
    }

    @GetMapping("/securities/{symbol}/metrics")
    public Page<Map<String,Object>> metrics(@PathVariable String symbol,
            @RequestParam(required=false) String exchange,
            @RequestParam(required=false) String codes,
            @RequestParam(required=false) String origin,
            @RequestParam(required=false) String period,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="50") int size) {
        return service.metrics(symbol,exchange,codes,origin,period,from,to,page,size);
    }

    @EndpointId("UserFinancialViewController_items")
    @GetMapping("/securities/{symbol}/statement-items")
    public Page<Map<String,Object>> items(@PathVariable String symbol,
            @RequestParam(required=false) String exchange,
            @RequestParam(defaultValue="INCOME_STATEMENT") String type,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="50") int size) {
        return service.items(symbol,exchange,type,page,size);
    }

    @EndpointId("UserFinancialViewController_matrix")
    @GetMapping("/securities/{symbol}/financials")
    public Data<Map<String,Object>> matrix(@PathVariable String symbol,
            @RequestParam(required=false) String exchange,
            @RequestParam(defaultValue="INCOME_STATEMENT") String type,
            @RequestParam(defaultValue="QUARTER") String period,
            @RequestParam(required=false) String scope,
            @RequestParam String codes,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue="12") int periodLimit) {
        return service.matrix(symbol,exchange,type,period,scope,codes,from,to,periodLimit);
    }

    @EndpointId("UserComparisonController_compare")
    @GetMapping("/securities/compare")
    public Data<Map<String,Object>> compare(@RequestParam String symbols,
            @RequestParam(required=false) String exchange,
            @RequestParam String codes,
            @RequestParam(defaultValue="PROVIDER") String origin,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.compare(symbols,exchange,codes,origin,asOf);
    }

    @EndpointId("UserDataStatusController_periods")
    @GetMapping("/securities/{symbol}/periods")
    public Data<List<Map<String,Object>>> periods(@PathVariable String symbol,
            @RequestParam(required=false) String exchange) {
        return service.periods(symbol,exchange);
    }

    @EndpointId("UserDataStatusController_lineage")
    @GetMapping("/securities/{symbol}/statements/{id}/lineage")
    public Data<Map<String,Object>> lineage(@PathVariable String symbol,
            @PathVariable UUID id,
            @RequestParam(required=false) String exchange) {
        return service.lineage(symbol,exchange,id);
    }

    @EndpointId("UserProvenanceController_metric")
    @GetMapping("/securities/{symbol}/metrics/{id}/lineage")
    public Data<Map<String,Object>> metricLineage(@PathVariable String symbol,
            @PathVariable UUID id,
            @RequestParam(required=false) String exchange) {
        return service.metricLineage(symbol,exchange,id);
    }
}
