package com.hethongdata.taichinh.controller.master;

import com.hethongdata.taichinh.dto.master.SecurityRequest;
import com.hethongdata.taichinh.dto.master.SecurityResponse;
import com.hethongdata.taichinh.dto.master.UpdateActivationRequest;
import com.hethongdata.taichinh.service.master.MasterDataService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/securities")
public class SecurityController {
    private final MasterDataService masterDataService;

    public SecurityController(MasterDataService masterDataService) {
        this.masterDataService = masterDataService;
    }

    @GetMapping
    public List<SecurityResponse> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return masterDataService.securities(activeOnly);
    }

    @PostMapping
    public ResponseEntity<SecurityResponse> create(@Valid @RequestBody SecurityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(masterDataService.createSecurity(request));
    }

    @PutMapping("/{securityId}")
    public SecurityResponse update(
            @PathVariable UUID securityId, @Valid @RequestBody SecurityRequest request) {
        return masterDataService.updateSecurity(securityId, request);
    }

    @PatchMapping("/{securityId}/activation")
    public SecurityResponse setActivation(
            @PathVariable UUID securityId, @Valid @RequestBody UpdateActivationRequest request) {
        return masterDataService.setSecurityActive(securityId, request.getActive());
    }

    @PostMapping("/{securityId}/ingestion-jobs/provision")
    public ResponseEntity<JobProvisionResponse> provision(@PathVariable UUID securityId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        new JobProvisionResponse(
                                securityId, masterDataService.provisionSecurity(securityId)));
    }

    public record JobProvisionResponse(UUID securityId, int affectedJobCount) {}
}
