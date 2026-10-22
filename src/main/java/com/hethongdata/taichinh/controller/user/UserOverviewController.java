package com.hethongdata.taichinh.controller.user;
import com.hethongdata.taichinh.service.user.*;
import com.hethongdata.taichinh.service.user.UserReadSupport.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1")
public class UserOverviewController {
  private final UserOverviewService service;
  public UserOverviewController(UserOverviewService service){this.service=service;}
  @GetMapping("/analysis/availability") public Data<Map<String,Object>> availability(){return service.availability();}
  @GetMapping("/securities/{symbol}/overview") public Data<Map<String,Object>> overview(@PathVariable String symbol,@RequestParam(required=false) String exchange){return service.overview(symbol,exchange);}
}
