package com.hethongdata.taichinh.controller.master;

import com.hethongdata.taichinh.dto.master.CompanyAliasRequest;
import com.hethongdata.taichinh.dto.master.CompanyAliasResponse;
import com.hethongdata.taichinh.dto.master.CompanyRequest;
import com.hethongdata.taichinh.dto.master.CompanyResponse;
import com.hethongdata.taichinh.service.master.MasterDataService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Admin API contract. Authentication is intentionally delegated to the future security module. */
@RestController
@RequestMapping("/api/companies")
public class CompanyController {
    private final MasterDataService masterDataService;
    public CompanyController(MasterDataService masterDataService) { this.masterDataService = masterDataService; }

    @GetMapping public List<CompanyResponse> list() { return masterDataService.companies(); }
    @PostMapping public ResponseEntity<CompanyResponse> create(@Valid @RequestBody CompanyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(masterDataService.createCompany(request));
    }
    @PutMapping("/{companyId}") public CompanyResponse update(@PathVariable UUID companyId, @Valid @RequestBody CompanyRequest request) {
        return masterDataService.updateCompany(companyId, request);
    }
    @GetMapping("/{companyId}/aliases") public List<CompanyAliasResponse> aliases(@PathVariable UUID companyId) {
        return masterDataService.aliases(companyId);
    }
    @PostMapping("/{companyId}/aliases") public ResponseEntity<CompanyAliasResponse> addAlias(
            @PathVariable UUID companyId, @Valid @RequestBody CompanyAliasRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(masterDataService.addAlias(companyId, request));
    }
}
