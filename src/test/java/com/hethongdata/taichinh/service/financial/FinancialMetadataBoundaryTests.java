package com.hethongdata.taichinh.service.financial;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.FinancialStatementEntity;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class FinancialMetadataBoundaryTests {
 private final ObjectMapper json=new ObjectMapper();
 @Test void missingMetadataIsAnExplicitWorklistNotInventedPublication() throws Exception {
  var m=FinancialStatementMetadata.parse(json.readTree("{}"),LocalDate.of(2026,6,30),"INCOME_STATEMENT");
  assertThat(m.provenance().path("missing_training_metadata").toString()).contains("published_at","report_scope","period_basis","original_release_proof","evidence_contract");
  assertThat(m.provenance().path("metadata_completeness").asText()).isEqualTo("INCOMPLETE");
  assertThat(m.provenance().path("automatically_certifies_historical_vintage").asBoolean()).isFalse();
  assertThat(m.publishedAt()).isNull();
 }
 @Test void periodMapCannotBorrowAnotherPeriodsPublication() throws Exception {
  var root=json.readTree("""
   {"provider":"vndirect","financial_metadata":{"schema_version":"financial.metadata.v1",
    "provider":"vndirect","periods":{"2026-03-31":{"schema_version":"financial.metadata.v1",
     "provider":"vndirect","published_at":"2026-04-25T09:00:00+07:00",
     "evidence":{"published_at":[{"normalized":"2026-04-25T09:00:00+07:00"}]}}}}}
   """);
  assertThat(FinancialStatementMetadata.parse(root,LocalDate.of(2026,3,31),"INCOME_STATEMENT").publishedAt()).isEqualTo(Instant.parse("2026-04-25T02:00:00Z"));
  assertThatThrownBy(()->FinancialStatementMetadata.parse(root,LocalDate.of(2026,6,30),"INCOME_STATEMENT")).hasMessageContaining("Missing per-period");
 }
 @Test void unknownMetadataNeverBecomesOneDongOrOriginalRevision() throws Exception {
  var m=FinancialStatementMetadata.parse(json.readTree("{}"),LocalDate.of(2026,6,30),"INCOME_STATEMENT");
  var s=FinancialStatementEntity.create(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"INCOME_STATEMENT","UNKNOWN",11L,UUID.randomUUID(),UUID.randomUUID(),null,1);s.applyMetadata(m);
  assertThat(s.getCurrency()).isNull();assertThat(s.getUnitScale()).isNull();assertThat(s.getIsRestated()).isNull();assertThat(s.getPublishedAt()).isNull();assertThat(s.getMetadataProvenance().path("contract").asText()).isEqualTo("LEGACY_UNVERIFIED");
 }
 @Test void explicitContractRequiresProviderIdentity() throws Exception {
  var root=json.readTree("{\"financial_metadata\":{\"schema_version\":\"financial.metadata.v1\"}}");
  assertThatThrownBy(()->FinancialStatementMetadata.parse(root,LocalDate.of(2026,6,30),"BALANCE_SHEET")).hasMessageContaining("provider mismatch");
 }
}
