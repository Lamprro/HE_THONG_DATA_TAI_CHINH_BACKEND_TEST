package com.hethongdata.taichinh.service.financial;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class OfficialFinancialSourcesTests {
 @Test void providerAndIssuerCannotBeMixed(){assertThat(OfficialFinancialSources.forSymbol("VNM").provider()).isEqualTo("vnm-ir");assertThat(OfficialFinancialSources.forSymbol("VNM").acceptsDocument("https://fpt.com/api/media/a.pdf")).isFalse();}
 @Test void encodedSpaceIsAllowedButTraversalIsNot(){var s=OfficialFinancialSources.forSymbol("ACB");assertThat(s.acceptsDocument("https://acb.com.vn/acbwebsite/files/report%202026.pdf")).isTrue();assertThat(s.acceptsDocument("https://acb.com.vn/acbwebsite/%2e%2e/a.pdf")).isFalse();assertThat(s.acceptsDocument("https://acb.com.vn.evil.test/acbwebsite/a.pdf")).isFalse();}
 @Test void yearAndPageQueriesAreBounded(){assertThat(OfficialFinancialSources.forSymbol("HPG").acceptsListing("https://www.hoaphat.com.vn/quan-he-co-dong/bao-cao-tai-chinh?page=100")).isTrue();assertThat(OfficialFinancialSources.forSymbol("HPG").acceptsListing("https://www.hoaphat.com.vn/quan-he-co-dong/bao-cao-tai-chinh?page=101")).isFalse();}
}
