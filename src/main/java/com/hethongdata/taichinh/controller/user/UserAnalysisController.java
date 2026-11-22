package com.hethongdata.taichinh.controller.user;

import com.hethongdata.taichinh.controller.user.UserContractController.EndpointId;
import com.hethongdata.taichinh.service.user.UserNewsService;
import com.hethongdata.taichinh.service.user.UserReadSupport.Data;
import com.hethongdata.taichinh.service.user.UserReadSupport.Page;
import java.time.LocalDate;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/** News, published AI analysis/scenarios reads. Operation IDs stay those of the former controllers. */
@RestController
@RequestMapping("/api/v1")
public class UserAnalysisController {
    private final UserNewsService news;

    public UserAnalysisController(UserNewsService news) {
        this.news = news;
    }

    @EndpointId("UserNewsController_news")
    @GetMapping("/news")
    public Page<Map<String,Object>> news(@RequestParam(required=false) String symbol,
            @RequestParam(required=false) String exchange,
            @RequestParam(required=false) String search,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size) {
        return news.news(symbol,exchange,search,from,to,page,size);
    }

    @EndpointId("UserNewsController_detail")
    @GetMapping("/news/{id}")
    public Data<Map<String,Object>> detail(@PathVariable UUID id) {
        return news.detail(id);
    }

    @EndpointId("UserNewsController_analysis")
    @GetMapping("/news/{id}/analysis")
    public Data<List<Map<String,Object>>> analysis(@PathVariable UUID id) {
        return news.analysis(id);
    }

    @EndpointId("UserNewsController_forecasts")
    @GetMapping("/securities/{symbol}/forecasts")
    public Data<List<Map<String,Object>>> forecasts(@PathVariable String symbol,
            @RequestParam(required=false) String exchange,
            @RequestParam(defaultValue="5") int limit) {
        return news.forecasts(symbol,exchange,limit);
    }
}
