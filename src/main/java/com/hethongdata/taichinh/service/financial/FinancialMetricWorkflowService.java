package com.hethongdata.taichinh.service.financial;

import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.service.validation.DataVersionLifecycleService;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FinancialMetricWorkflowService {
    public static final String WORKFLOW="FINANCIAL_METRIC_BUILD";
    private final DataVersionJpaRepository versions;
    private final IngestionRunRepository runs;
    private final FinancialMetricBuildPersistenceService writes;
    private final DataVersionLifecycleService lifecycle;
    public FinancialMetricWorkflowService(DataVersionJpaRepository versions, IngestionRunRepository runs,
            FinancialMetricBuildPersistenceService writes,DataVersionLifecycleService lifecycle) {
        this.versions=versions;this.runs=runs;this.writes=writes;this.lifecycle=lifecycle;
    }
    public boolean supports(String code) {return WORKFLOW.equals(code);}
    public IngestionExecutionResponse execute(IngestionJobEntity job,String trigger) {
        var candidates=versions.findByDataDomainAndStatusOrderByCreatedAtAsc("FINANCIAL_METRIC","ACTIVE");
        UUID last=null;int rejected=0;
        if (candidates.isEmpty()) {
            var run=runs.startInternalBatch(job.getDataSource(),job,trigger,WORKFLOW);
            writes.noWork(run);last=run.getId();
        }
        for (var v:candidates) {
            var run=runs.startInternalBatch(job.getDataSource(),job,trigger,WORKFLOW);last=run.getId();
            try {writes.build(v.getId(),run);}
            catch (RuntimeException e) {
                runs.markFailed(run,"PROTOCOL",null,WORKFLOW+" failed for version "+v.getId());
                lifecycle.rejectBuildFailure(v.getId(),WORKFLOW,e);rejected++;
            }
        }
        return new IngestionExecutionResponse(last,null,rejected==0?"SUCCESS":"COMPLETED_WITH_REJECTIONS",200,"application/json",null,false);
    }
}
