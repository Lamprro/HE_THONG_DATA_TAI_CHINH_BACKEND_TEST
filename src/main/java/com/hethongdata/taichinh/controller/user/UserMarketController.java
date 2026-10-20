package com.hethongdata.taichinh.controller.user;
import com.hethongdata.taichinh.service.user.*;
import com.hethongdata.taichinh.service.user.UserReadSupport.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1")
public class UserMarketController {
  private final UserMarketService service;
  public UserMarketController(UserMarketService service) {this.service=service;}
  @GetMapping("/market/overview") public Data<Map<String,Object>> overview(@RequestParam(required=false) String exchange,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate asOf) {return service.overview(exchange,asOf);}
  @GetMapping("/securities/{symbol}/prices") public Page<Map<String,Object>> prices(@PathVariable String symbol,@RequestParam(required=false) String exchange,@RequestParam(defaultValue="1d") String interval,
      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="250") int size) {return service.prices(symbol,exchange,interval,from,to,page,size);}
  @GetMapping("/securities/{symbol}/quote") public Data<Map<String,Object>> quote(@PathVariable String symbol,@RequestParam(required=false) String exchange) {return service.latest(symbol,exchange);}
  @GetMapping("/indices") public Page<Map<String,Object>> indices(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size) {return service.indices(page,size);}
  @GetMapping("/indices/{code}/prices") public Page<Map<String,Object>> prices(@PathVariable String code,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="250") int size) {return service.indexPrices(code,from,to,page,size);}
  @GetMapping("/indices/{code}/members") public Page<Map<String,Object>> members(@PathVariable String code,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate asOf,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size) {return service.members(code,asOf,page,size);}
  @GetMapping("/macro/series") public Page<Map<String,Object>> macroSeries(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size) {return service.macroSeries(page,size);}
  @GetMapping("/macro/series/{id}/observations") public Page<Map<String,Object>> macro(@PathVariable UUID id,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="250") int size) {return service.macro(id,from,to,page,size);}
}
