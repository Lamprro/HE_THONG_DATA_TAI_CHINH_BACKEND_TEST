package com.hethongdata.taichinh.service.market;
import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.service.validation.DataVersionLifecycleService;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SupplementaryDataWorkflowService {
    private final DataVersionJpaRepository versions;private final IngestionRunRepository runs;
    private final SupplementaryDataPersistenceService writes;private final DataVersionLifecycleService lifecycle;
    public SupplementaryDataWorkflowService(DataVersionJpaRepository versions,IngestionRunRepository runs,
        SupplementaryDataPersistenceService writes,DataVersionLifecycleService lifecycle) {
        this.versions=versions;this.runs=runs;this.writes=writes;this.lifecycle=lifecycle;
    }
    public boolean supports(String code) {return Set.of("FOREIGN_TRADE_BUILD","SECURITY_REFERENCE_BUILD").contains(code);}
    public IngestionExecutionResponse execute(IngestionJobEntity job,String trigger) {
        String domain="FOREIGN_TRADE_BUILD".equals(job.getCode())?"MARKET_FOREIGN":"SECURITY_REFERENCE";
        var candidates=versions.findByDataDomainAndStatusOrderByCreatedAtAsc(domain,"ACTIVE");UUID last=null;int rejected=0;
        if(candidates.isEmpty()) {var run=runs.startInternalBatch(job.getDataSource(),job,trigger,job.getCode());writes.noWork(run);last=run.getId();}
        for(var version:candidates) {
            var run=runs.startInternalBatch(job.getDataSource(),job,trigger,job.getCode());last=run.getId();
            try {writes.build(version.getId(),domain,run);}
            catch(RuntimeException e) {runs.markFailed(run,"PROTOCOL",null,job.getCode()+" failed for "+version.getId());lifecycle.rejectBuildFailure(version.getId(),job.getCode(),e);rejected++;}
        }
        return new IngestionExecutionResponse(last,null,rejected==0?"SUCCESS":"COMPLETED_WITH_REJECTIONS",200,"application/json",null,false);
    }
}
