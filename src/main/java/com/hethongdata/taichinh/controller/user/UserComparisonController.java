package com.hethongdata.taichinh.controller.user;
import com.hethongdata.taichinh.service.user.*;
import com.hethongdata.taichinh.service.user.UserReadSupport.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1")
public class UserComparisonController {
  private final UserComparisonService service;
  public UserComparisonController(UserComparisonService service){this.service=service;}
  @GetMapping("/market/screener") public Page<Map<String,Object>> screen(@RequestParam(required=false) String search,@RequestParam(required=false) String exchange,@RequestParam(required=false) String industry,
    @RequestParam(required=false) String metric,@RequestParam(defaultValue="PROVIDER") String origin,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate asOf,
    @RequestParam(required=false) String min,@RequestParam(required=false) String max,@RequestParam(defaultValue="SYMBOL") String sort,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return service.screen(search,exchange,industry,metric,origin,asOf,min,max,sort,page,size);}
  @GetMapping("/securities/compare") public Data<Map<String,Object>> compare(@RequestParam String symbols,@RequestParam(required=false) String exchange,@RequestParam String codes,@RequestParam(defaultValue="PROVIDER") String origin,
    @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate asOf){return service.compare(symbols,exchange,codes,origin,asOf);}
}
