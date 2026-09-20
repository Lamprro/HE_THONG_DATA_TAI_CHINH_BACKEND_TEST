package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.FinancialPeriodEntity;
import com.hethongdata.taichinh.entity.FinancialStatementEntity;
import com.hethongdata.taichinh.entity.FinancialStatementItemEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.jpa.financial.FinancialPeriodJpaRepository;
import com.hethongdata.taichinh.repository.jpa.financial.FinancialStatementItemJpaRepository;
import com.hethongdata.taichinh.repository.jpa.financial.FinancialStatementJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns the all-or-nothing database commit for one validated financial-statement batch. */
@Service
public class FinancialStatementBuildPersistenceService {
    private final DataVersionJpaRepository versions;
    private final IngestionRunJpaRepository ingestionRuns;
    private final FinancialPeriodJpaRepository periods;
    private final FinancialStatementJpaRepository statements;
    private final FinancialStatementItemJpaRepository items;
    private final SecurityJpaRepository securities;
    private final ObjectMapper objectMapper;

    public FinancialStatementBuildPersistenceService(
            DataVersionJpaRepository versions,
            IngestionRunJpaRepository ingestionRuns,
            FinancialPeriodJpaRepository periods,
            FinancialStatementJpaRepository statements,
            FinancialStatementItemJpaRepository items,
            SecurityJpaRepository securities,
            ObjectMapper objectMapper) {
        this.versions = versions;
        this.ingestionRuns = ingestionRuns;
        this.periods = periods;
        this.statements = statements;
        this.items = items;
        this.securities = securities;
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
            FinancialStatementEntity statement =
                    statements.save(
                            FinancialStatementEntity.create(
                                    security.getCompanyId(),
                                    security.getId(),
                                    period.getId(),
                                    draft.statementType(),
                                    draft.reportScope(),
                                    draft.rawPayload().getDataSource().getId(),
                                    draft.rawPayload().getId(),
                                    version.getId(),
                                    draft.rawPayload().getPublishedAt()));
            for (FinancialStatementBuildService.ItemDraft item : draft.items()) {
                items.save(
                        FinancialStatementItemEntity.create(
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
                                                "provider", item.provider() == null ? "UNKNOWN" : item.provider()))));
            }
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
