package com.hethongdata.taichinh.controller.user;
import com.hethongdata.taichinh.service.user.*;
import com.hethongdata.taichinh.service.user.UserReadSupport.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/securities/{symbol}")
public class UserProvenanceController {
  private final UserProvenanceService service;
  public UserProvenanceController(UserProvenanceService service) {this.service=service;}
  @GetMapping("/prices/{id}/lineage")
  public Data<Map<String,Object>> price(@PathVariable String symbol,@PathVariable long id,@RequestParam(required=false) String exchange) {return service.price(symbol,exchange,id);}
  @GetMapping("/metrics/{id}/lineage")
  public Data<Map<String,Object>> metric(@PathVariable String symbol,@PathVariable UUID id,@RequestParam(required=false) String exchange) {return service.metric(symbol,exchange,id);}
}
