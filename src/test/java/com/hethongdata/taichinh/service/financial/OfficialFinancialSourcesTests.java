package com.hethongdata.taichinh.service.financial;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class OfficialFinancialSourcesTests {
 @Test void providerAndIssuerCannotBeMixed(){assertThat(OfficialFinancialSources.forSymbol("VNM").provider()).isEqualTo("vnm-ir");assertThat(OfficialFinancialSources.forSymbol("VNM").acceptsDocument("https://fpt.com/api/media/a.pdf")).isFalse();}
 @Test void encodedSpaceIsAllowedButTraversalIsNot(){var s=OfficialFinancialSources.forSymbol("ACB");assertThat(s.acceptsDocument("https://acb.com.vn/acbwebsite/files/report%202026.pdf")).isTrue();assertThat(s.acceptsDocument("https://acb.com.vn/acbwebsite/%2e%2e/a.pdf")).isFalse();assertThat(s.acceptsDocument("https://acb.com.vn.evil.test/acbwebsite/a.pdf")).isFalse();}
 @Test void yearAndPageQueriesAreBounded(){assertThat(OfficialFinancialSources.forSymbol("HPG").acceptsListing("https://www.hoaphat.com.vn/quan-he-co-dong/bao-cao-tai-chinh?page=100")).isTrue();assertThat(OfficialFinancialSources.forSymbol("HPG").acceptsListing("https://www.hoaphat.com.vn/quan-he-co-dong/bao-cao-tai-chinh?page=101")).isFalse();}
 @Test void issuerUrlsAllowNamesButRejectEncodedPaths(){
  var source=OfficialFinancialSources.forSymbol("EIB");
  String root="https://media.eximbank.com.vn/exim/files/";
  assertThat(source.acceptsDocument(root+"report%20%281%29.pdf")).isTrue();
  for(String name:java.util.List.of("%2e%2e/a.pdf","x%2fy.pdf","x%5cy.pdf","%252e%252e/a.pdf","x%00.pdf"))
   assertThat(source.acceptsDocument(root+name)).isFalse();
 }
 @Test void hsgUsesItsObservedPaginationQuery(){
  var source=OfficialFinancialSources.forSymbol("HSG");
  assertThat(source.acceptsListing(source.listing()+"?pg=2")).isTrue();
  assertThat(source.acceptsListing(source.listing()+"?pg=101")).isFalse();
 }
}
