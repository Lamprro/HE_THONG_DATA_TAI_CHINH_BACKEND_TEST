package com.hethongdata.taichinh.scheduler.master;

import com.hethongdata.taichinh.service.master.MasterDataService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Optional safety-net only; normal provisioning happens immediately when a security is made active.
 */
@Component
@ConditionalOnProperty(
        prefix = "financial.security-job-reconciliation.scheduler",
        name = "enabled",
        havingValue = "true")
public class SecurityJobReconciliationScheduler {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(SecurityJobReconciliationScheduler.class);
    private final MasterDataService masterDataService;

    public SecurityJobReconciliationScheduler(MasterDataService masterDataService) {
        this.masterDataService = masterDataService;
    }

    @Scheduled(
            fixedDelayString =
                    "${financial.security-job-reconciliation.scheduler.poll-interval:86400000}")
    public void reconcile() {
        LOGGER.info(
                "Reconciled {} active-security job definitions",
                masterDataService.reconcileActiveSecurities());
    }
}
