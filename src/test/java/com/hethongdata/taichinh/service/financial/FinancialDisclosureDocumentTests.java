package com.hethongdata.taichinh.service.financial;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FinancialDisclosureDocumentTests {
 static ObjectNode fixture()throws Exception {
  return (ObjectNode)new ObjectMapper().readTree("""
   {"schema_version":"financial.disclosures.v1","provider":"fpt-ir","symbol":"FPT",
   "source_url":"https://fpt.com/vi/nha-dau-tu/thong-tin-cong-bo","source_sha256":"HASH","count":1,
   "data":[{"document_id":"1","source_record_sha256":"HASH","symbol":"FPT","report_version_id":"FPT_IR:1",
   "document_url":"https://fpt.com/api/media/report.pdf","period_end":"2025-06-30","published_on":"2025-07-22",
   "publication_evidence":{"field":"actualPublishedAt","value":"2025-07-22"},"publication_precision":"DAY",
   "available_at":"2025-07-22T23:59:59.999999+07:00","report_scope":"CONSOLIDATED","revision_status":"UNKNOWN",
   "title":"Báo cáo hợp nhất quý 2 2025","scope_evidence":{"value":"Báo cáo hợp nhất quý 2 2025"},
   "source_record":{"id":1,"actualPublishedAt":"2025-07-22","name":"Báo cáo hợp nhất quý 2 2025","pdfUrl":"/api/media/report.pdf"},
   "training_eligible":false,"vendor_value_binding":"UNVERIFIED","vintage_status":"ARCHIVED_DISCLOSURE_RECONSTRUCTION"}]}
   """.replace("HASH","a".repeat(64)));
 }
 @Test void validArchivedMetadataIsAcceptedWithoutTrainingCertification()throws Exception {FinancialDisclosureDocumentStore.validate(fixture());}
 @Test void recordIdentityMustMatchEvidence()throws Exception {var r=fixture();((ObjectNode)r.path("data").get(0).path("source_record")).put("id",2);assertThatThrownBy(()->FinancialDisclosureDocumentStore.validate(r)).hasMessage("DOCUMENT_ID_EVIDENCE");}
 @Test void arbitraryDateCannotBeQuarterEnd()throws Exception {var r=fixture();((ObjectNode)r.path("data").get(0)).put("period_end","2025-06-15");assertThatThrownBy(()->FinancialDisclosureDocumentStore.validate(r)).hasMessage("QUARTER_END");}
 @Test void originalVersionCannotBeInvented()throws Exception {var r=fixture();((ObjectNode)r.path("data").get(0)).put("revision_status","ORIGINAL");assertThatThrownBy(()->FinancialDisclosureDocumentStore.validate(r)).hasMessage("REVISION_STATUS");}
 @Test void earlierAvailabilityRejected()throws Exception {var r=fixture();((ObjectNode)r.path("data").get(0)).put("available_at","2025-07-22T00:00:00+07:00");assertThatThrownBy(()->FinancialDisclosureDocumentStore.validate(r)).hasMessage("DAY_PRECISION_AVAILABILITY");}
 @Test void missingScopeEvidenceRejected()throws Exception {var r=fixture();((ObjectNode)r.path("data").get(0)).put("report_scope","SEPARATE");assertThatThrownBy(()->FinancialDisclosureDocumentStore.validate(r)).hasMessage("SEPARATE_EVIDENCE");}
 @Test void wrongPublicationEvidenceRejected()throws Exception {var r=fixture();((ObjectNode)r.path("data").get(0).path("source_record")).put("actualPublishedAt","2025-07-21");assertThatThrownBy(()->FinancialDisclosureDocumentStore.validate(r)).hasMessage("PUBLICATION_EVIDENCE");}
 @Test void trainingEligibilityCannotBeAutoEnabled()throws Exception {var r=fixture();((ObjectNode)r.path("data").get(0)).put("training_eligible",true);assertThatThrownBy(()->FinancialDisclosureDocumentStore.validate(r)).hasMessage("VINTAGE_NOT_CERTIFIED");}
 @Test void documentHostCannotEscapeOfficialSource()throws Exception {var r=fixture();((ObjectNode)r.path("data").get(0)).put("document_url","https://other.example/report.pdf");assertThatThrownBy(()->FinancialDisclosureDocumentStore.validate(r)).hasMessage("DOCUMENT_URL");}
}
