package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.*;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomic materialization of validated RATIO batches, with source selection and replay audit. */
@Service
public class FinancialMetricBuildPersistenceService {
    private final JdbcTemplate db;
    private final DataVersionJpaRepository versions;
    private final RawPayloadJpaRepository raws;
    private final IngestionRunJpaRepository runs;
    private final ProviderRatioParser parser;
    private final ObjectMapper mapper;
    private final ChecksumService hashes;
    public FinancialMetricBuildPersistenceService(JdbcTemplate db, DataVersionJpaRepository versions,
            RawPayloadJpaRepository raws, IngestionRunJpaRepository runs, ProviderRatioParser parser,
            ObjectMapper mapper, ChecksumService hashes) {
        this.db=db; this.versions=versions; this.raws=raws; this.runs=runs; this.parser=parser;
        this.mapper=mapper; this.hashes=hashes;
    }
    @Transactional
    public int build(UUID versionId, IngestionRunEntity run) {
        var version=versions.findByIdForUpdate(versionId).orElseThrow();
        if (!"ACTIVE".equals(version.getStatus()) || !"FINANCIAL_METRIC".equals(version.getDataDomain()))
            throw new IllegalStateException("Version is no longer an active FINANCIAL_METRIC batch");
        var payloads=raws.findByIngestionRunIdOrderByFetchedAtDesc(version.getIngestionRunId());
        if (payloads.isEmpty()) throw new IllegalArgumentException("No raw ratio payloads");
        int inserted=0;
        for (var raw:payloads) {
            if (!"RATIO".equals(raw.getEntityType())) throw new IllegalArgumentException("Batch contains non-RATIO payload");
            var rows=parser.parse(raw.getPayload(),raw.getSourceSymbol());
            var security=db.queryForList("SELECT id,company_id FROM securities WHERE upper(symbol)=upper(?)",
                    raw.getSourceSymbol());
            if (security.size()!=1 || security.getFirst().get("company_id")==null)
                throw new IllegalArgumentException("Missing company/security mapping");
            UUID securityId=(UUID)security.getFirst().get("id");
            UUID companyId=(UUID)security.getFirst().get("company_id");
            if (raw.getSecurityId()!=null && !raw.getSecurityId().equals(securityId))
                throw new IllegalArgumentException("Raw security ID differs from requested symbol");
            db.queryForList("SELECT id FROM companies WHERE id=? FOR UPDATE",companyId);
            for (var ratio:rows) {
                Long definition=db.queryForObject("SELECT id FROM metric_definitions WHERE code=?",Long.class,ratio.code());
                var snapshot=mapper.createObjectNode().put("contract","vndirect-ratio-v1")
                        .put("sourceRawId",raw.getId().toString()).put("sourceChecksum",raw.getChecksumSha256())
                        .put("sourceValue",ratio.sourceValue()).put("transformation",ratio.transformation())
                        .put("sourceAvailableAt",raw.getFetchedAt().toString());
                snapshot.set("sourceRow",ratio.sourceRow());
                String key=hashes.sha256("provider-ratio-v1:"+raw.getId()+":"+ratio.code()+":"+ratio.date());
                inserted+=db.update("""
INSERT INTO financial_metrics(company_id,security_id,metric_definition_id,as_of_date,value,
    data_source_id,raw_payload_id,data_version_id,is_derived,is_canonical,calculation_version,
    quality_status,input_snapshot,calculation_key)
VALUES (?,?,?,?,?,?,?,?,false,false,'provider-ratio-v1','VALID',?::jsonb,?)
ON CONFLICT (calculation_key) WHERE calculation_key IS NOT NULL DO NOTHING
""",companyId,securityId,definition,ratio.date(),ratio.value(),raw.getDataSource().getId(),
                        raw.getId(),versionId,snapshot.toString(),key);
                reconcile(db,companyId,definition,ratio.date());
            }
        }
        run.markBatchSuccess(mapper.valueToTree(Map.of("workflow","FINANCIAL_METRIC_BUILD",
                "sourceVersionId",versionId,"metricCount",inserted)),Instant.now(),inserted);
        runs.save(run);version.markActivated();versions.save(version);return inserted;
    }
    static void reconcile(JdbcTemplate db, UUID company, Long definition, LocalDate date) {
        var winners=db.queryForList("""
SELECT m.id FROM financial_metrics m LEFT JOIN data_sources d ON d.id=m.data_source_id
LEFT JOIN raw_payloads r ON r.id=m.raw_payload_id
WHERE m.company_id=? AND m.metric_definition_id=? AND m.as_of_date=? AND m.quality_status='VALID'
ORDER BY d.is_official DESC NULLS LAST,d.priority ASC NULLS LAST,d.id ASC NULLS LAST,
    coalesce(r.fetched_at,m.created_at) DESC,m.created_at DESC,m.id LIMIT 1
""",company,definition,date);
        if (winners.isEmpty()) return;
        db.update("UPDATE financial_metrics SET is_canonical=false WHERE company_id=? AND metric_definition_id=? AND as_of_date=? AND is_canonical",company,definition,date);
        db.update("UPDATE financial_metrics SET is_canonical=true WHERE id=?",winners.getFirst().get("id"));
    }
    @Transactional
    public void noWork(IngestionRunEntity run) {
        run.markBatchSuccess(mapper.valueToTree(Map.of("workflow","FINANCIAL_METRIC_BUILD","noWork",true)),Instant.now(),0);
        runs.save(run);
    }
}
