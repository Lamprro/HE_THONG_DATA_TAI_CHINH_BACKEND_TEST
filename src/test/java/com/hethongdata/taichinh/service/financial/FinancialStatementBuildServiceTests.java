package com.hethongdata.taichinh.service.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.application.port.error.ExternalErrorCategory;
import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.service.validation.DataVersionLifecycleService;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class FinancialStatementBuildServiceTests {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesProviderArrayIntoOneQuarterlyStatementAndItems() throws Exception {
        RawPayloadEntity raw = mock(RawPayloadEntity.class);
        when(raw.getPayload())
                .thenReturn(
                        objectMapper.readTree(
                                """
                                {"symbol":"FPT","dataset":"income_statement","report_type":"QUARTER","data":[
                                  {"fiscalDate":"2026-06-30","itemCode":23100,"itemEnName":"Gross profit","numericValue":6518866479000,"displayOrder":2}
                                ]}
                                """));

        List<FinancialStatementBuildService.StatementDraft> drafts = service().parse(raw);

        assertThat(drafts).hasSize(1);
        FinancialStatementBuildService.StatementDraft statement = drafts.getFirst();
        assertThat(statement.symbol()).isEqualTo("FPT");
        assertThat(statement.statementType()).isEqualTo("INCOME_STATEMENT");
        assertThat(statement.period().fiscalYear()).isEqualTo(2026);
        assertThat(statement.period().periodType()).isEqualTo("Q2");
        assertThat(statement.period().startDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(statement.period().endDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(statement.items())
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.itemCode()).isEqualTo("GROSS_PROFIT");
                            assertThat(item.value()).isEqualByComparingTo(new BigDecimal("6518866479000"));
                        });
    }

    @Test
    void parsesPeriodKeyedPayloadIntoStatementItems() throws Exception {
        RawPayloadEntity raw = mock(RawPayloadEntity.class);
        when(raw.getPayload())
                .thenReturn(
                        objectMapper.readTree(
                                """
                                {"symbol":"FPT","dataset":"cash_flow","data":{
                                  "gross_profit":{"2026-Q2":6518866479000},
                                  "operating_cash_flow":{"2026-Q2":"1200"}
                                }}
                                """));

        List<FinancialStatementBuildService.StatementDraft> drafts = service().parse(raw);

        assertThat(drafts).hasSize(1);
        FinancialStatementBuildService.StatementDraft statement = drafts.getFirst();
        assertThat(statement.statementType()).isEqualTo("CASH_FLOW");
        assertThat(statement.period().periodType()).isEqualTo("Q2");
        assertThat(statement.period().endDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(statement.items()).extracting(FinancialStatementBuildService.ItemDraft::itemCode)
                .containsExactly("GROSS_PROFIT", "OPERATING_CASH_FLOW");
    }

    @Test
    void rejectsAnEmptyValidatedBatchInsteadOfActivatingIt() throws Exception {
        RawPayloadEntity raw = mock(RawPayloadEntity.class);
        when(raw.getPayload())
                .thenReturn(objectMapper.readTree("{\"symbol\":\"FPT\",\"dataset\":\"cash_flow\",\"data\":[]}"));

        assertThat(service().parse(raw)).isEmpty();
    }

    @Test
    void disambiguatesProviderRowsThatHaveTheSameReadableItemName() throws Exception {
        RawPayloadEntity raw = mock(RawPayloadEntity.class);
        when(raw.getPayload())
                .thenReturn(
                        objectMapper.readTree(
                                """
                                {"symbol":"FPT","dataset":"balance_sheet","data":[
                                  {"fiscalDate":"2026-06-30","itemCode":12000,"itemEnName":"Other current assets","numericValue":1},
                                  {"fiscalDate":"2026-06-30","itemCode":12001,"itemEnName":"Other current assets","numericValue":2}
                                ]}
                                """));

        assertThat(service().parse(raw).getFirst().items())
                .extracting(FinancialStatementBuildService.ItemDraft::itemCode)
                .allSatisfy(code -> assertThat(code).startsWith("OTHER_CURRENT_ASSETS_"));
    }

    @Test
    void transposesVnStockPeriodColumnsAndDisambiguatesDuplicateItemIdsDeterministically() throws Exception {
        String payload =
                """
                {"symbol":"FPT","dataset":"income_statement","provider":"vnstock","data":[
                  {"item":"3. Doanh thu thuần về bán hàng và cung cấp dịch vụ","item_id":"revenue","2025-Q3":10,"2026-Q2":30},
                  {"item":"1. Doanh thu bán hàng và cung cấp dịch vụ","item_id":"revenue","2025-Q3":20,"2026-Q2":40}
                ]}
                """;
        RawPayloadEntity raw = mock(RawPayloadEntity.class);
        when(raw.getPayload()).thenReturn(objectMapper.readTree(payload));

        List<FinancialStatementBuildService.StatementDraft> first = service().parse(raw);
        List<FinancialStatementBuildService.StatementDraft> second = service().parse(raw);

        assertThat(first).hasSize(2);
        assertThat(first).extracting(draft -> draft.period().periodType()).containsExactly("Q3", "Q2");
        for (FinancialStatementBuildService.StatementDraft statement : first) {
            assertThat(statement.items()).hasSize(2);
            assertThat(statement.items()).extracting(FinancialStatementBuildService.ItemDraft::itemCode)
                    .doesNotHaveDuplicates()
                    .allSatisfy(code -> assertThat(code).startsWith("REVENUE_"));
            assertThat(statement.items()).extracting(FinancialStatementBuildService.ItemDraft::sourceItemCode)
                    .containsOnly("revenue");
            assertThat(statement.items()).extracting(FinancialStatementBuildService.ItemDraft::provider)
                    .containsOnly("vnstock");
        }
        assertThat(second).isEqualTo(first);
    }

    @Test
    void recordsFailedBuildRunOutsideTheRollingBackPersistenceTransaction() throws Exception {
        DataVersionJpaRepository versions = mock(DataVersionJpaRepository.class);
        RawPayloadJpaRepository rawPayloads = mock(RawPayloadJpaRepository.class);
        IngestionRunRepository runs = mock(IngestionRunRepository.class);
        FinancialStatementBuildPersistenceService writes = mock(FinancialStatementBuildPersistenceService.class);
        DataVersionLifecycleService lifecycle = mock(DataVersionLifecycleService.class);
        IngestionJobEntity job = mock(IngestionJobEntity.class);
        DataSourceEntity source = mock(DataSourceEntity.class);
        DataVersionEntity version = mock(DataVersionEntity.class);
        RawPayloadEntity raw = mock(RawPayloadEntity.class);
        IngestionRunEntity buildRun = mock(IngestionRunEntity.class);
        UUID versionId = UUID.randomUUID();
        UUID sourceRunId = UUID.randomUUID();

        when(job.getDataSource()).thenReturn(source);
        when(versions.findByDataDomainAndStatusOrderByCreatedAtAsc("FINANCIAL_STATEMENT", "ACTIVE"))
                .thenReturn(List.of(version));
        when(version.getId()).thenReturn(versionId);
        when(version.getIngestionRunId()).thenReturn(sourceRunId);
        when(runs.startInternalBatch(source, job, "MANUAL", FinancialStatementBuildService.FINANCIAL_STATEMENT_BUILD))
                .thenReturn(buildRun);
        when(rawPayloads.findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(sourceRunId, "FINANCIAL_STATEMENT"))
                .thenReturn(List.of(raw));
        when(raw.getPayload()).thenReturn(objectMapper.readTree("{\"symbol\":\"FPT\",\"dataset\":\"cash_flow\",\"data\":{\"operating_cash_flow\":{\"2026-Q2\":1}}}"));
        doThrow(new IllegalStateException("forced persistence failure"))
                .when(writes)
                .persist(eq(versionId), eq(buildRun), anyList());

        FinancialStatementBuildService service =
                new FinancialStatementBuildService(versions, rawPayloads, runs, writes, lifecycle);

        IngestionExecutionResponse response = service.execute(job, "MANUAL");

        assertThat(response.getStatus()).isEqualTo("COMPLETED_WITH_REJECTIONS");
        verify(runs)
                .markFailed(
                        eq(buildRun),
                        eq(ExternalErrorCategory.PROTOCOL.name()),
                        isNull(),
                        eq("FINANCIAL_STATEMENT_BUILD failed for version " + versionId));
        verify(lifecycle)
                .rejectBuildFailure(
                        eq(versionId),
                        eq(FinancialStatementBuildService.FINANCIAL_STATEMENT_BUILD),
                        org.mockito.ArgumentMatchers.any(IllegalStateException.class));
    }

    @Test
    void assignsTheSameDuplicateItemCodesWhenProviderChangesRowOrder() throws Exception {
        RawPayloadEntity first = mock(RawPayloadEntity.class);
        RawPayloadEntity reversed = mock(RawPayloadEntity.class);
        when(first.getPayload())
                .thenReturn(
                        objectMapper.readTree(
                                """
                                {"symbol":"FPT","dataset":"income_statement","provider":"vnstock","data":[
                                  {"item":"Revenue line A","item_id":"revenue","2026-Q2":10},
                                  {"item":"Revenue line B","item_id":"revenue","2026-Q2":20}
                                ]}
                                """));
        when(reversed.getPayload())
                .thenReturn(
                        objectMapper.readTree(
                                """
                                {"symbol":"FPT","dataset":"income_statement","provider":"vnstock","data":[
                                  {"item":"Revenue line B","item_id":"revenue","2026-Q2":20},
                                  {"item":"Revenue line A","item_id":"revenue","2026-Q2":10}
                                ]}
                                """));

        assertThat(codesBySourceName(service().parse(first)))
                .isEqualTo(codesBySourceName(service().parse(reversed)));
    }

    private FinancialStatementBuildService service() {
        return new FinancialStatementBuildService(
                mock(DataVersionJpaRepository.class),
                mock(RawPayloadJpaRepository.class),
                mock(IngestionRunRepository.class),
                mock(FinancialStatementBuildPersistenceService.class),
                mock(DataVersionLifecycleService.class));
    }

    private static Map<String, String> codesBySourceName(
            List<FinancialStatementBuildService.StatementDraft> drafts) {
        Map<String, String> result = new LinkedHashMap<>();
        for (FinancialStatementBuildService.StatementDraft draft : drafts) {
            for (FinancialStatementBuildService.ItemDraft item : draft.items()) {
                result.put(item.sourceItemName(), item.itemCode());
            }
        }
        return result;
    }
}
