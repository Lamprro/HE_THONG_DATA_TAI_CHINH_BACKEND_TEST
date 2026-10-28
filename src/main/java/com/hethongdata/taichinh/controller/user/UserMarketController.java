package com.hethongdata.taichinh.controller.user;

import com.hethongdata.taichinh.service.user.UserMarketService;
import com.hethongdata.taichinh.service.user.UserComparisonService;
import com.hethongdata.taichinh.service.user.UserDataStatusService;
import com.hethongdata.taichinh.service.user.UserProvenanceService;
import com.hethongdata.taichinh.service.user.UserSupplementaryService;
import com.hethongdata.taichinh.service.user.UserReadSupport.Data;
import com.hethongdata.taichinh.service.user.UserReadSupport.Page;
import com.hethongdata.taichinh.controller.user.UserContractController.EndpointId;
import java.time.LocalDate;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class UserMarketController {
    private final UserMarketService service;
    private final UserComparisonService comparison;
    private final UserDataStatusService coverage;
    private final UserProvenanceService provenance;
    private final UserSupplementaryService supplementary;

    public UserMarketController(UserMarketService service,
            UserComparisonService comparison,
            UserDataStatusService coverage,
            UserProvenanceService provenance,
            UserSupplementaryService supplementary) {
        this.service = service;
        this.comparison = comparison;
        this.coverage = coverage;
        this.provenance = provenance;
        this.supplementary = supplementary;
    }

    @GetMapping("/market/overview")
    public Data<Map<String,Object>> overview(@RequestParam(required=false) String exchange,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.overview(exchange,asOf);
    }

    @GetMapping("/securities/{symbol}/prices")
    public Page<Map<String,Object>> prices(@PathVariable String symbol,
            @RequestParam(required=false) String exchange,
            @RequestParam(defaultValue="1d") String interval,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="250") int size) {
        return service.prices(symbol,exchange,interval,from,to,page,size);
    }

    @GetMapping("/securities/{symbol}/quote")
    public Data<Map<String,Object>> quote(@PathVariable String symbol,
            @RequestParam(required=false) String exchange) {
        return service.latest(symbol,exchange);
    }

    @GetMapping("/indices")
    public Page<Map<String,Object>> indices(@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size) {
        return service.indices(page,size);
    }

    @GetMapping("/indices/{code}/prices")
    public Page<Map<String,Object>> prices(@PathVariable String code,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="250") int size) {
        return service.indexPrices(code,from,to,page,size);
    }

    @GetMapping("/indices/{code}/members")
    public Page<Map<String,Object>> members(@PathVariable String code,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="100") int size) {
        return service.members(code,asOf,page,size);
    }

    @GetMapping("/macro/series")
    public Page<Map<String,Object>> macroSeries(@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size) {
        return service.macroSeries(page,size);
    }

    @GetMapping("/macro/series/{id}/observations")
    public Page<Map<String,Object>> macro(@PathVariable UUID id,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="250") int size) {
        return service.macro(id,from,to,page,size);
    }

    @EndpointId("UserComparisonController_screen")
    @GetMapping("/market/screener")
    public Page<Map<String,Object>> screen(@RequestParam(required=false) String search,
            @RequestParam(required=false) String exchange,
            @RequestParam(required=false) String industry,
            @RequestParam(required=false) String metric,
            @RequestParam(defaultValue="PROVIDER") String origin,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required=false) String min,
            @RequestParam(required=false) String max,
            @RequestParam(defaultValue="SYMBOL") String sort,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size) {
        return comparison.screen(search,exchange,industry,metric,origin,asOf,min,max,sort,page,size);
    }

    @EndpointId("UserDataStatusController_filters")
    @GetMapping("/market/filters")
    public Data<Map<String,Object>> filters() {
        return coverage.filters();
    }

    @EndpointId("UserProvenanceController_price")
    @GetMapping("/securities/{symbol}/prices/{id}/lineage")
    public Data<Map<String,Object>> priceLineage(@PathVariable String symbol,
            @PathVariable long id,
            @RequestParam(required=false) String exchange) {
        return provenance.price(symbol,exchange,id);
    }

    @EndpointId("UserSupplementaryController_foreign")
    @GetMapping("/securities/{symbol}/foreign-trades")
    public Page<Map<String,Object>> foreign(@PathVariable String symbol,
            @RequestParam(required=false) String exchange,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="50") int size) {
        return supplementary.foreign(symbol,exchange,from,to,page,size);
    }

    @EndpointId("UserSupplementaryController_reference")
    @GetMapping("/securities/{symbol}/reference-observations")
    public Page<Map<String,Object>> reference(@PathVariable String symbol,
            @RequestParam(required=false) String exchange,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size) {
        return supplementary.reference(symbol,exchange,from,to,page,size);
    }
}
