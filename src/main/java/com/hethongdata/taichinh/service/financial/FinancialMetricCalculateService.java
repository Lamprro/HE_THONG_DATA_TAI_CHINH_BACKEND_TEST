package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.entity.*;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository;
import com.hethongdata.taichinh.repository.jpa.financial.FinancialMetricJpaRepository;
import com.hethongdata.taichinh.repository.jpa.financial.MetricDefinitionJpaRepository;
import com.hethongdata.taichinh.repository.jpa.financial.*;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*; import java.time.Instant; import java.util.*;

/** Recalculates type-safe derived metrics from current, activated statement sets. */
@Service public class FinancialMetricCalculateService {
    public static final String WORKFLOW = "FINANCIAL_METRIC_CALCULATE"; public static final String CALCULATION_VERSION = "FS_ITEMS_V1";
    private static final MathContext DIVISION = MathContext.DECIMAL128;
    private final FinancialStatementJpaRepository statements; private final FinancialStatementItemJpaRepository items; private final FinancialPeriodJpaRepository periods;
    private final DataVersionJpaRepository versions; private final MetricDefinitionJpaRepository definitions; private final FinancialMetricJpaRepository metrics;
    private final IngestionRunRepository runs; private final ObjectMapper objectMapper;
    public FinancialMetricCalculateService(FinancialStatementJpaRepository statements, FinancialStatementItemJpaRepository items, FinancialPeriodJpaRepository periods, DataVersionJpaRepository versions, MetricDefinitionJpaRepository definitions, FinancialMetricJpaRepository metrics, IngestionRunRepository runs, ObjectMapper objectMapper) { this.statements=statements; this.items=items; this.periods=periods; this.versions=versions; this.definitions=definitions; this.metrics=metrics; this.runs=runs; this.objectMapper=objectMapper; }
    public boolean supports(String code) { return WORKFLOW.equals(code); }
    public IngestionExecutionResponse execute(IngestionJobEntity job, String trigger) { IngestionRunEntity run = runs.startInternalBatch(job.getDataSource(), job, trigger, WORKFLOW); Counts counts = calculate(); run.markWorkflowSuccess(objectMapper.valueToTree(Map.of("workflow", WORKFLOW, "candidateGroups", counts.groups, "calculated", counts.calculated, "unchanged", counts.unchanged, "skipped", counts.skipped)), Instant.now(), counts.calculated, counts.inserted, counts.updated); return new IngestionExecutionResponse(run.getId(), null, "SUCCESS", 200, "application/json", null, false); }
    @Transactional public Counts calculate() {
        List<FinancialStatementEntity> current = statements.findByIsCurrentTrue().stream().filter(this::activated).toList();
        Map<GroupKey, List<FinancialStatementEntity>> groups = new LinkedHashMap<>();
        for (FinancialStatementEntity statement : current) groups.computeIfAbsent(new GroupKey(statement.getCompanyId(), statement.getSecurityId(), statement.getFinancialPeriodId(), statement.getDataSourceId()), k -> new ArrayList<>()).add(statement);
        int calculated=0, inserted=0, updated=0, unchanged=0, skipped=0;
        for (var entry : groups.entrySet()) { List<FinancialStatementEntity> selected = preferredScope(entry.getValue()); if (selected.isEmpty()) continue; Map<String, FinancialStatementEntity> byType = new HashMap<>(); for (FinancialStatementEntity s : selected) byType.putIfAbsent(s.getStatementType(), s); List<UUID> ids=selected.stream().map(FinancialStatementEntity::getId).toList(); Map<UUID, String> types=new HashMap<>(); selected.forEach(s -> types.put(s.getId(),s.getStatementType())); Map<String, BigDecimal> values=new HashMap<>(); for (FinancialStatementItemEntity item : items.findByFinancialStatementIdIn(ids)) values.putIfAbsent(types.get(item.getFinancialStatementId()) + ":" + item.getItemCode(), item.getValue()); FinancialPeriodEntity period=periods.findById(entry.getKey().periodId).orElseThrow();
            for (Formula formula : formulas()) { BigDecimal value=formula.value(values); if (value == null) { skipped++; continue; } calculated++; MetricDefinitionEntity definition=definitions.findByCode(formula.code).orElse(null); if (definition==null) { skipped++; continue; } List<FinancialMetricEntity> existing=metrics.findDerivedIdentity(entry.getKey().companyId,entry.getKey().securityId,entry.getKey().periodId,definition.getId(),entry.getKey().sourceId,CALCULATION_VERSION); if(existing.isEmpty()){metrics.save(FinancialMetricEntity.derived(entry.getKey().companyId,entry.getKey().securityId,entry.getKey().periodId,definition.getId(),period.getEndDate(),value,entry.getKey().sourceId,CALCULATION_VERSION));inserted++;} else if(existing.getFirst().getValue().compareTo(value)==0) unchanged++; else { existing.getFirst().recalculate(value); updated++; }
            }
        } return new Counts(groups.size(),calculated,inserted,updated,unchanged,skipped);
    }
    private boolean activated(FinancialStatementEntity statement) { return statement.getDataVersionId()!=null && versions.findById(statement.getDataVersionId()).map(v -> "FINANCIAL_STATEMENT".equals(v.getDataDomain()) && "ACTIVATED".equals(v.getStatus())).orElse(false); }
    private static List<FinancialStatementEntity> preferredScope(List<FinancialStatementEntity> values) { for(String scope:List.of("CONSOLIDATED","SEPARATE","UNKNOWN")){List<FinancialStatementEntity> picked=values.stream().filter(s->scope.equals(s.getReportScope())).toList(); if(!picked.isEmpty()) return picked;} return List.of(); }
    private static List<Formula> formulas() { return List.of(
            percent("GROSS_MARGIN","INCOME_STATEMENT:GROSS_PROFIT","INCOME_STATEMENT:NET_SALES"), percent("OPERATING_MARGIN","INCOME_STATEMENT:NET_PROFIT_FROM_OPERATING_ACTIVITIES","INCOME_STATEMENT:NET_SALES"), percent("NET_MARGIN","INCOME_STATEMENT:NET_PROFIT_AFTER_TAX","INCOME_STATEMENT:NET_SALES"), ratio("CURRENT_RATIO","BALANCE_SHEET:CURRENT_ASSETS","BALANCE_SHEET:SHORT_TERM_LIABILITIES"), ratio("CASH_RATIO","BALANCE_SHEET:CASH_AND_CASH_EQUIVALENTS","BALANCE_SHEET:SHORT_TERM_LIABILITIES"), ratio("LIABILITIES_TO_EQUITY","BALANCE_SHEET:LIABILITIES","BALANCE_SHEET:OWNERS_EQUITY")); }
    private static Formula ratio(String code,String n,String d){return new Formula(code,n,d,false);} private static Formula percent(String code,String n,String d){return new Formula(code,n,d,true);}
    private record Formula(String code,String numerator,String denominator,boolean percentage){BigDecimal value(Map<String,BigDecimal> values){BigDecimal n=values.get(numerator),d=values.get(denominator);if(n==null||d==null||d.signum()==0)return null;BigDecimal result=n.divide(d,DIVISION);return percentage?result.movePointRight(2):result;}}
    private record GroupKey(UUID companyId,UUID securityId,UUID periodId,Long sourceId){} public record Counts(int groups,int calculated,int inserted,int updated,int unchanged,int skipped){}
}
