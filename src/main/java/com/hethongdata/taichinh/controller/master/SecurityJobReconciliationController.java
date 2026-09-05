package com.hethongdata.taichinh.controller.master;

import com.hethongdata.taichinh.service.master.MasterDataService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit admin trigger for an idempotent scan of active securities. */
@RestController
@RequestMapping("/api/security-job-reconciliation")
public class SecurityJobReconciliationController {
    private final MasterDataService masterDataService;

    public SecurityJobReconciliationController(MasterDataService masterDataService) {
        this.masterDataService = masterDataService;
    }

    @PostMapping("/run")
    public ResponseEntity<ReconciliationResponse> run() {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ReconciliationResponse(masterDataService.reconcileActiveSecurities()));
    }

    public record ReconciliationResponse(int provisionedJobCount) {}
}
