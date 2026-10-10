package com.hethongdata.taichinh.service.financial;

import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import org.springframework.stereotype.Service;

/** Compatibility entry point; provider metrics have one audited writer and lifecycle. */
@Service
public class FinancialMetricBuildService {
    public static final String WORKFLOW = FinancialMetricWorkflowService.WORKFLOW;
    private final FinancialMetricWorkflowService workflow;
    public FinancialMetricBuildService(FinancialMetricWorkflowService workflow) { this.workflow = workflow; }
    public boolean supports(String code) { return workflow.supports(code); }
    public IngestionExecutionResponse execute(IngestionJobEntity job, String trigger) {
        return workflow.execute(job, trigger);
    }
}
