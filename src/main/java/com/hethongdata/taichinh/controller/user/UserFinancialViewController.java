package com.hethongdata.taichinh.controller.user;
import com.hethongdata.taichinh.service.user.*;
import com.hethongdata.taichinh.service.user.UserReadSupport.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/securities/{symbol}")
public class UserFinancialViewController {
  private final UserFinancialViewService service;
  public UserFinancialViewController(UserFinancialViewService service) {this.service=service;}
  @GetMapping("/statement-items") public Page<Map<String,Object>> items(@PathVariable String symbol,@RequestParam(required=false) String exchange,@RequestParam(defaultValue="INCOME_STATEMENT") String type,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size) {return service.items(symbol,exchange,type,page,size);}
  @GetMapping("/financials") public Data<Map<String,Object>> matrix(@PathVariable String symbol,@RequestParam(required=false) String exchange,@RequestParam(defaultValue="INCOME_STATEMENT") String type,
      @RequestParam(defaultValue="QUARTER") String period,@RequestParam(required=false) String scope,@RequestParam String codes,
      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@RequestParam(defaultValue="12") int periodLimit) {
    return service.matrix(symbol,exchange,type,period,scope,codes,from,to,periodLimit);
  }
}
