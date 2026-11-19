package com.hethongdata.taichinh.controller.master;

import com.hethongdata.taichinh.dto.master.MasterDataDtos.CompanyAliasRequest;
import com.hethongdata.taichinh.dto.master.MasterDataDtos.CompanyAliasResponse;
import com.hethongdata.taichinh.dto.master.MasterDataDtos.CompanyRequest;
import com.hethongdata.taichinh.dto.master.MasterDataDtos.CompanyResponse;
import com.hethongdata.taichinh.dto.master.MasterDataDtos.SecurityRequest;
import com.hethongdata.taichinh.dto.master.MasterDataDtos.SecurityResponse;
import com.hethongdata.taichinh.dto.master.MasterDataDtos.UpdateActivationRequest;
import com.hethongdata.taichinh.service.master.MasterDataService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin master-data routes: companies ({@code /api/companies}), securities ({@code
 * /api/securities}) and the explicit, idempotent active-security job reconciliation trigger
 * ({@code /api/security-job-reconciliation/run}).
 */
@RestController
public class MasterDataController {
    private final MasterDataService masterDataService;

    public MasterDataController(MasterDataService masterDataService) {
        this.masterDataService = masterDataService;
    }

    // --- /api/companies

    @GetMapping("/api/companies")
    public List<CompanyResponse> companies() {
        return masterDataService.companies();
    }

    @PostMapping("/api/companies")
    public ResponseEntity<CompanyResponse> createCompany(@Valid @RequestBody CompanyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(masterDataService.createCompany(request));
    }

    @PutMapping("/api/companies/{companyId}")
    public CompanyResponse updateCompany(
            @PathVariable UUID companyId, @Valid @RequestBody CompanyRequest request) {
        return masterDataService.updateCompany(companyId, request);
    }

    @GetMapping("/api/companies/{companyId}/aliases")
    public List<CompanyAliasResponse> aliases(@PathVariable UUID companyId) {
        return masterDataService.aliases(companyId);
    }

    @PostMapping("/api/companies/{companyId}/aliases")
    public ResponseEntity<CompanyAliasResponse> addAlias(
            @PathVariable UUID companyId, @Valid @RequestBody CompanyAliasRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(masterDataService.addAlias(companyId, request));
    }

    // --- /api/securities

    @GetMapping("/api/securities")
    public List<SecurityResponse> securities(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return masterDataService.securities(activeOnly);
    }

    @PostMapping("/api/securities")
    public ResponseEntity<SecurityResponse> createSecurity(@Valid @RequestBody SecurityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(masterDataService.createSecurity(request));
    }

    @PutMapping("/api/securities/{securityId}")
    public SecurityResponse updateSecurity(
            @PathVariable UUID securityId, @Valid @RequestBody SecurityRequest request) {
        return masterDataService.updateSecurity(securityId, request);
    }

    @PatchMapping("/api/securities/{securityId}/activation")
    public SecurityResponse setSecurityActivation(
            @PathVariable UUID securityId, @Valid @RequestBody UpdateActivationRequest request) {
        return masterDataService.setSecurityActive(securityId, request.active());
    }

    @PostMapping("/api/securities/{securityId}/ingestion-jobs/provision")
    public ResponseEntity<JobProvisionResponse> provision(@PathVariable UUID securityId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        new JobProvisionResponse(
                                securityId, masterDataService.provisionSecurity(securityId)));
    }

    // --- /api/security-job-reconciliation

    @PostMapping("/api/security-job-reconciliation/run")
    public ResponseEntity<ReconciliationResponse> reconcile() {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ReconciliationResponse(masterDataService.reconcileActiveSecurities()));
    }

    public record JobProvisionResponse(UUID securityId, int affectedJobCount) {}

    public record ReconciliationResponse(int provisionedJobCount) {}
}
