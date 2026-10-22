package com.hethongdata.taichinh.controller.user;
import com.hethongdata.taichinh.service.user.*;
import com.hethongdata.taichinh.service.user.UserReadSupport.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/securities/{symbol}")
public class UserSupplementaryController {
  private final UserSupplementaryService service;
  public UserSupplementaryController(UserSupplementaryService service){this.service=service;}
  @GetMapping("/foreign-trades") public Page<Map<String,Object>> foreign(@PathVariable String symbol,@RequestParam(required=false) String exchange,
    @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
    @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return service.foreign(symbol,exchange,from,to,page,size);}
  @GetMapping("/reference-observations") public Page<Map<String,Object>> reference(@PathVariable String symbol,@RequestParam(required=false) String exchange,
    @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
    @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return service.reference(symbol,exchange,from,to,page,size);}
}
