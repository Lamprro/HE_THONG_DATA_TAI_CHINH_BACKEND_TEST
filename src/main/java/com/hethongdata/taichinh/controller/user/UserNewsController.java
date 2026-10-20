package com.hethongdata.taichinh.controller.user;
import com.hethongdata.taichinh.service.user.*;
import com.hethongdata.taichinh.service.user.UserReadSupport.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1")
public class UserNewsController {
  private final UserNewsService news;private final UserAnalysisService analysis;
  public UserNewsController(UserNewsService news,UserAnalysisService analysis) {this.news=news;this.analysis=analysis;}
  @GetMapping("/news") public Page<Map<String,Object>> news(@RequestParam(required=false) String symbol,@RequestParam(required=false) String exchange,@RequestParam(required=false) String search,
      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size) {return news.news(symbol,exchange,search,from,to,page,size);}
  @GetMapping("/news/{id}") public Data<Map<String,Object>> detail(@PathVariable UUID id) {return news.detail(id);}
  @GetMapping("/news/{id}/analysis") public Data<List<Map<String,Object>>> analysis(@PathVariable UUID id) {return analysis.news(id);}
  @GetMapping("/securities/{symbol}/forecasts") public Data<List<Map<String,Object>>> forecasts(@PathVariable String symbol,@RequestParam(required=false) String exchange,@RequestParam(defaultValue="5") int limit) {return analysis.forecasts(symbol,exchange,limit);}
}
