package com.hethongdata.taichinh.controller.user;
import com.hethongdata.taichinh.service.user.*;
import com.hethongdata.taichinh.service.user.UserReadSupport.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1")
public class UserLocalAiController {
  private final UserLocalAiService service;
  public UserLocalAiController(UserLocalAiService service){this.service=service;}
  @GetMapping("/local-ai/models") public Page<Map<String,Object>> models(@RequestParam(required=false) UUID packageId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return service.models(packageId,page,size);}
  @GetMapping("/securities/{symbol}/local-ai") public Data<Map<String,Object>> security(@PathVariable String symbol,@RequestParam(required=false) String exchange,@RequestParam(required=false) UUID packageId){return service.security(symbol,exchange,packageId);}
}
