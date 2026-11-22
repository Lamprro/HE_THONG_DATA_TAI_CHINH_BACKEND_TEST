package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.FinancialPeriodEntity;
import com.hethongdata.taichinh.entity.FinancialStatementEntity;
import com.hethongdata.taichinh.entity.FinancialStatementItemEntity;
import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.jpa.financial.FinancialPeriodJpaRepository;
import com.hethongdata.taichinh.repository.jpa.financial.FinancialStatementItemJpaRepository;
import com.hethongdata.taichinh.repository.jpa.financial.FinancialStatementJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.DataSourceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.Set;
import com.hethongdata.taichinh.service.validation.DataVersionLifecycleService;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns the all-or-nothing database commit for one validated financial-statement batch. */
@Service
public class FinancialStatementBuildPersistenceService {
    private static final Set<String> REVIEW_ITEMS = Set.of("NET_SALES", "REVENUE", "NET_PROFIT_AFTER_TAX");
    private static final BigDecimal REVIEW_TOLERANCE = new BigDecimal("0.001");
    private final DataVersionJpaRepository versions;
    private final IngestionRunJpaRepository ingestionRuns;
    private final FinancialPeriodJpaRepository periods;
    private final FinancialStatementJpaRepository statements;
    private final FinancialStatementItemJpaRepository items;
    private final SecurityJpaRepository securities;
    private final DataSourceJpaRepository sources;
    private final ObjectMapper objectMapper;

    public FinancialStatementBuildPersistenceService(
            DataVersionJpaRepository versions,
            IngestionRunJpaRepository ingestionRuns,
            FinancialPeriodJpaRepository periods,
            FinancialStatementJpaRepository statements,
            FinancialStatementItemJpaRepository items,
            SecurityJpaRepository securities,
            DataSourceJpaRepository sources,
            ObjectMapper objectMapper) {
        this.versions = versions;
        this.ingestionRuns = ingestionRuns;
        this.periods = periods;
        this.statements = statements;
        this.items = items;
        this.securities = securities;
        this.sources = sources;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public int persist(
            UUID dataVersionId,
            IngestionRunEntity buildRun,
            List<FinancialStatementBuildService.StatementDraft> drafts) {
        DataVersionEntity version = activeVersion(dataVersionId);
        int inserted = 0;
        for (FinancialStatementBuildService.StatementDraft draft : drafts) {
            SecurityEntity security = resolveSecurity(draft.rawPayload(), draft.symbol());
            if (security == null || security.getCompanyId() == null) {
                throw new IllegalStateException("No company/security mapping for financial statement symbol " + draft.symbol());
            }
            securities.findByIdForUpdate(security.getId()).orElseThrow();
            FinancialPeriodEntity period =
                    periods
                            .findByFiscalYearAndPeriodTypeAndEndDate(
                                    (short) draft.period().fiscalYear(),
                                    draft.period().periodType(),
                                    draft.period().endDate())
                            .orElseGet(
                                    () ->
                                            periods.save(
                                                    FinancialPeriodEntity.create(
                                                            (short) draft.period().fiscalYear(),
                                                            draft.period().periodType(),
                                                            draft.period().startDate(),
                                                            draft.period().endDate(),
                                                            draft.period().endDate(),
                                                            false)));
            if (statements
                    .findByRawPayloadIdAndFinancialPeriodIdAndStatementType(
                            draft.rawPayload().getId(), period.getId(), draft.statementType())
                    .isPresent()) {
                continue;
            }
            if (version.getReviewedAt() == null) checkSourceConflict(security.getCompanyId(), period.getId(), draft);
            var current = statements
                    .findFirstByCompanyIdAndFinancialPeriodIdAndStatementTypeAndReportScopeAndDataSourceIdAndIsCurrentTrue(
                            security.getCompanyId(), period.getId(), draft.statementType(),
                            draft.reportScope(), draft.rawPayload().getDataSource().getId());
            int revisionNo = 1;
            if (current.isPresent()) {
                FinancialStatementEntity previous = current.get();
                revisionNo = previous.getRevisionNo() + 1;
                previous.supersede(Instant.now());
                // Flush the current=false transition before inserting the replacement because
                // PostgreSQL enforces the partial unique index at statement execution time.
                statements.saveAndFlush(previous);
            }
            FinancialStatementEntity statement =
                            FinancialStatementEntity.create(
                                    security.getCompanyId(),
                                    security.getId(),
                                    period.getId(),
                                    draft.statementType(),
                                    draft.reportScope(),
                                    draft.rawPayload().getDataSource().getId(),
                                    draft.rawPayload().getId(),
                                    version.getId(),
                                    draft.metadata().publishedAt(),
                                    revisionNo);
            statement.applyMetadata(draft.metadata());
            statement = statements.save(statement);
            for (FinancialStatementBuildService.ItemDraft item : draft.items()) {
                items.save(
                        FinancialStatementItemEntity.createWithUnit(
                                statement.getId(),
                                item.itemCode(),
                                item.itemName(),
                                item.value(),
                                item.rawValue(),
                                item.displayOrder(),
                                objectMapper.valueToTree(
                                        Map.of(
                                                "sourceItemId", item.sourceItemCode(),
                                                "sourceItemName", item.sourceItemName(),
                                                "provider", item.provider() == null ? "UNKNOWN" : item.provider())),
                                draft.metadata().currency()));
            }
            statements.flush();
            reconcileCanonical(security.getCompanyId(), period.getId(), draft.statementType(), draft.reportScope(),
                    version.getReviewedAt() != null ? statement.getId() : null);
            inserted++;
        }
        buildRun.markBatchSuccess(
                objectMapper.valueToTree(
                        Map.of(
                                "workflow", FinancialStatementBuildService.FINANCIAL_STATEMENT_BUILD,
                                "sourceVersionId", dataVersionId,
                                "statementCount", inserted)),
                Instant.now(),
                inserted);
        ingestionRuns.save(buildRun);
        version.markActivated();
        versions.save(version);
        return inserted;
    }

    private void checkSourceConflict(UUID company, UUID period, FinancialStatementBuildService.StatementDraft draft) {
        if (!"INCOME_STATEMENT".equals(draft.statementType()) || draft.metadata().currency() == null
                || "UNKNOWN".equals(draft.reportScope()) || "UNKNOWN".equals(draft.metadata().periodBasis())) return;
        var reference = statements.findCurrentBucketForUpdate(company, period, draft.statementType(), draft.reportScope())
                .stream().filter(row -> Boolean.TRUE.equals(row.getIsCanonical())
                        && !row.getDataSourceId().equals(draft.rawPayload().getDataSource().getId())
                        && draft.metadata().currency().equals(row.getCurrency())
                        && draft.metadata().periodBasis().equals(row.getPeriodBasis()))
                .findFirst();
        if (reference.isEmpty()) return;
        var baseline = items.findByFinancialStatementIdIn(List.of(reference.get().getId()));
        for (var candidate : draft.items()) {
            if (!REVIEW_ITEMS.contains(candidate.itemCode()) || candidate.value() == null) continue;
            var values = baseline.stream().filter(item -> candidate.itemCode().equals(item.getItemCode())
                    && item.getValue() != null).toList();
            if (values.size() != 1) continue;
            DataVersionLifecycleService.requireMatchingSource(draft.rawPayload().getId(),
                    draft.symbol() + " " + draft.period().endDate() + " " + candidate.itemCode(),
                    candidate.value(), values.getFirst().getValue(), REVIEW_TOLERANCE);
        }
    }

    private void reconcileCanonical(UUID companyId, UUID periodId, String statementType, String reportScope,
            UUID approvedStatement) {
        List<FinancialStatementEntity> bucket = statements.findCurrentBucketForUpdate(
                companyId, periodId, statementType, reportScope);
        if (bucket.isEmpty()) throw new IllegalStateException("Không tìm thấy báo cáo tài chính vừa ghi");
        Map<Long, DataSourceEntity> sourceById = new HashMap<>();
        for (FinancialStatementEntity row : bucket) {
            sourceById.computeIfAbsent(row.getDataSourceId(), id -> sources.findById(id)
                    .orElseThrow(() -> new IllegalStateException("Không tìm thấy data source: " + id)));
        }
        FinancialStatementEntity winner = bucket.stream().min(Comparator
                .comparing((FinancialStatementEntity row) -> !row.getId().equals(approvedStatement))
                .thenComparing(row -> !sourceById.get(row.getDataSourceId()).isOfficial())
                .thenComparing(row -> sourceById.get(row.getDataSourceId()).getPriority())
                .thenComparing(FinancialStatementEntity::getDataSourceId)).orElseThrow();
        for (FinancialStatementEntity row : bucket) row.setCanonical(false);
        statements.saveAllAndFlush(bucket);
        winner.setCanonical(true);
        statements.saveAndFlush(winner);
    }

    @Transactional
    public void noWork(IngestionRunEntity run) {
        run.markBatchSuccess(
                objectMapper.valueToTree(
                        Map.of("workflow", FinancialStatementBuildService.FINANCIAL_STATEMENT_BUILD, "noWork", true)),
                Instant.now(),
                0);
        ingestionRuns.save(run);
    }

    private DataVersionEntity activeVersion(UUID id) {
        DataVersionEntity version =
                versions.findByIdForUpdate(id).orElseThrow(() -> new IllegalArgumentException("Data version was not found"));
        if (!"FINANCIAL_STATEMENT".equals(version.getDataDomain()) || !"ACTIVE".equals(version.getStatus())) {
            throw new IllegalStateException("Data version is no longer an ACTIVE FINANCIAL_STATEMENT batch");
        }
        return version;
    }

    private SecurityEntity resolveSecurity(RawPayloadEntity raw, String symbol) {
        if (raw.getSecurityId() != null) return securities.findById(raw.getSecurityId()).orElse(null);
        return securities.findBySymbolIgnoreCase(symbol).orElse(null);
    }
}
