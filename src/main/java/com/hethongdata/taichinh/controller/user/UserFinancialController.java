package com.hethongdata.taichinh.controller.user;
import com.hethongdata.taichinh.service.user.*;
import com.hethongdata.taichinh.service.user.UserReadSupport.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1")
public class UserFinancialController {
  private final UserFinancialService service;
  public UserFinancialController(UserFinancialService service) {this.service=service;}
  @GetMapping("/securities/{symbol}/statements") public Page<Map<String,Object>> statements(@PathVariable String symbol,@RequestParam(required=false) String exchange,@RequestParam(required=false) String type,@RequestParam(required=false) String period,
      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size) {return service.statements(symbol,exchange,type,period,from,to,page,size);}
  @GetMapping("/securities/{symbol}/statements/{id}") public Data<Map<String,Object>> statement(@PathVariable String symbol,@PathVariable UUID id,@RequestParam(required=false) String exchange) {return service.statement(symbol,exchange,id);}
  @GetMapping("/metrics") public Page<Map<String,Object>> definitions(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size) {return service.definitions(page,size);}
  @GetMapping("/securities/{symbol}/metrics") public Page<Map<String,Object>> metrics(@PathVariable String symbol,@RequestParam(required=false) String exchange,@RequestParam(required=false) String codes,@RequestParam(required=false) String origin,@RequestParam(required=false) String period,
      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size) {return service.metrics(symbol,exchange,codes,origin,period,from,to,page,size);}
}
