package com.hethongdata.taichinh.controller.admin;

import com.hethongdata.taichinh.service.financial.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/admin/financial-data")
public class FinancialDataQualityAdminController {
 private final FinancialDataQualityService service;private final FinancialDisclosureDocumentStore documents;
 public FinancialDataQualityAdminController(FinancialDataQualityService service,FinancialDisclosureDocumentStore documents){this.service=service;this.documents=documents;}
 public record RefreshRequest(String symbol,Integer year){}
 @PostMapping("/disclosures/refresh") public Map<String,Object> refresh(@RequestBody RefreshRequest request){return service.refresh(request.symbol(),request.year());}
 @GetMapping("/disclosures") public List<Map<String,Object>> list(@RequestParam String symbol,@RequestParam(defaultValue="100") int limit){return documents.list(symbol,limit);}
 @GetMapping("/status") public Map<String,Object> status(@RequestParam String symbol){return service.status(symbol);}
}
