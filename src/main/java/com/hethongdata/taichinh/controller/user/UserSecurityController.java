package com.hethongdata.taichinh.controller.user;

import com.hethongdata.taichinh.service.user.UserSecurityService;
import com.hethongdata.taichinh.service.user.UserDataStatusService;
import com.hethongdata.taichinh.service.user.UserOverviewService;
import com.hethongdata.taichinh.service.user.UserReadSupport.Data;
import com.hethongdata.taichinh.service.user.UserReadSupport.Page;
import com.hethongdata.taichinh.controller.user.UserContractController.EndpointId;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class UserSecurityController {
    private final UserSecurityService service;
    private final UserDataStatusService coverage;
    private final UserOverviewService overview;

    public UserSecurityController(UserSecurityService service,
            UserDataStatusService coverage,
            UserOverviewService overview) {
        this.service = service;
        this.coverage = coverage;
        this.overview = overview;
    }

    @GetMapping("/securities")
    public Page<Map<String,Object>> securities(
            @RequestParam(required=false) String search,
              @RequestParam(required=false) String exchange,
              @RequestParam(defaultValue="true") boolean activeOnly,
              @RequestParam(defaultValue="0") int page,
              @RequestParam(defaultValue="25") int size) {
        return service.securities(search,exchange,activeOnly,page,size);
    }

    @GetMapping("/securities/{symbol}")
    public Data<Map<String,Object>> security(@PathVariable String symbol,
              @RequestParam(required=false) String exchange) {
        return service.detail(symbol,exchange);
    }

    @GetMapping("/companies/{id}")
    public Data<Map<String,Object>> company(@PathVariable UUID id) {
        return service.company(id);
    }

    @GetMapping("/companies")
    public Page<Map<String,Object>> companies(@RequestParam(required=false) String search,
              @RequestParam(defaultValue="true") boolean activeOnly,
              @RequestParam(defaultValue="0") int page,
              @RequestParam(defaultValue="25") int size) {
        return service.companies(search,activeOnly,page,size);
    }

    @EndpointId("UserDataStatusController_status")
    @GetMapping("/securities/{symbol}/data-status")
    public Data<Map<String,Object>> status(@PathVariable String symbol,
              @RequestParam(required=false) String exchange) {
        return coverage.status(symbol,exchange);
    }

    @EndpointId("UserOverviewController_availability")
    @GetMapping("/analysis/availability")
    public Data<Map<String,Object>> availability() {
        return overview.availability();
    }

    @EndpointId("UserOverviewController_overview")
    @GetMapping("/securities/{symbol}/overview")
    public Data<Map<String,Object>> overview(@PathVariable String symbol,
              @RequestParam(required=false) String exchange) {
        return overview.overview(symbol,exchange);
    }
}
