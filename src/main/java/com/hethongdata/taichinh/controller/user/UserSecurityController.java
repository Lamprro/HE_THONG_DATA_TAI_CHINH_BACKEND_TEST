package com.hethongdata.taichinh.controller.user;

import com.hethongdata.taichinh.service.user.*;
import com.hethongdata.taichinh.service.user.UserReadSupport.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class UserSecurityController {
  private final UserSecurityService service;
  public UserSecurityController(UserSecurityService service) {this.service=service;}
  @GetMapping("/securities") public Page<Map<String,Object>> securities(
      @RequestParam(required=false) String search,@RequestParam(required=false) String exchange,
      @RequestParam(defaultValue="true") boolean activeOnly,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size) {
    return service.securities(search,exchange,activeOnly,page,size);
  }
  @GetMapping("/securities/{symbol}") public Data<Map<String,Object>> security(@PathVariable String symbol,@RequestParam(required=false) String exchange) {return service.detail(symbol,exchange);}
  @GetMapping("/companies/{id}") public Data<Map<String,Object>> company(@PathVariable UUID id) {return service.company(id);}
  @GetMapping("/companies") public Page<Map<String,Object>> companies(@RequestParam(required=false) String search,@RequestParam(defaultValue="true") boolean activeOnly,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return service.companies(search,activeOnly,page,size);}
}
