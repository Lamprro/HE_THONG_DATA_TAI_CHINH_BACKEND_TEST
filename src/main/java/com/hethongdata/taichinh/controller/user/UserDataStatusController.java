package com.hethongdata.taichinh.controller.user;
import com.hethongdata.taichinh.service.user.*;
import com.hethongdata.taichinh.service.user.UserReadSupport.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1")
public class UserDataStatusController {
  private final UserDataStatusService service;
  public UserDataStatusController(UserDataStatusService service) {this.service=service;}
  @GetMapping("/securities/{symbol}/data-status") public Data<Map<String,Object>> status(@PathVariable String symbol,@RequestParam(required=false) String exchange) {return service.status(symbol,exchange);}
  @GetMapping("/securities/{symbol}/periods") public Data<List<Map<String,Object>>> periods(@PathVariable String symbol,@RequestParam(required=false) String exchange) {return service.periods(symbol,exchange);}
  @GetMapping("/securities/{symbol}/statements/{id}/lineage") public Data<Map<String,Object>> lineage(@PathVariable String symbol,@PathVariable UUID id,@RequestParam(required=false) String exchange) {return service.lineage(symbol,exchange,id);}
  @GetMapping("/market/filters") public Data<Map<String,Object>> filters() {return service.filters();}
}
