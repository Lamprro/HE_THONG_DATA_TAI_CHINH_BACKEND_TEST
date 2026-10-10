package com.hethongdata.taichinh.service.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.master.CompanyAliasEntity;
import com.hethongdata.taichinh.entity.master.CompanyEntity;
import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.repository.jpa.master.CompanyAliasJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.CompanyJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

class NewsCompanyMatcherTests {
    @Test
    void matchesSeveralKnownCompaniesFromCleanArticleNotFromSourceJob() {
        CompanyJpaRepository companies = mock(CompanyJpaRepository.class);
        CompanyAliasJpaRepository aliases = mock(CompanyAliasJpaRepository.class);
        SecurityJpaRepository securities = mock(SecurityJpaRepository.class);
        UUID fptCompanyId = UUID.randomUUID();
        UUID acbCompanyId = UUID.randomUUID();
        UUID bidCompanyId = UUID.randomUUID();
        UUID fptSecurityId = UUID.randomUUID();
        UUID acbSecurityId = UUID.randomUUID();
        CompanyEntity fpt = company(fptCompanyId, "FPT", "Công ty cổ phần FPT", true);
        CompanyEntity acb = company(acbCompanyId, "ACB", "Ngân hàng Á Châu", true);
        CompanyEntity bid = company(bidCompanyId, "BID", "Ngân hàng BIDV", true);
        when(companies.findAll()).thenReturn(List.of(fpt, acb, bid));
        when(aliases.findAll()).thenReturn(List.of(
                CompanyAliasEntity.create(acbCompanyId, "Ngân hàng Á Châu", "NEWS_ALIAS")));
        when(securities.findAll()).thenReturn(List.of(
                security(fptSecurityId, fptCompanyId, "FPT", true),
                security(acbSecurityId, acbCompanyId, "ACB", true),
                security(UUID.randomUUID(), bidCompanyId, "BID", true)));

        NewsCompanyMatcher matcher = new NewsCompanyMatcher(companies, aliases, securities, new ObjectMapper());
        NewsArticleDraft article = new NewsArticleDraft("https://cafef.vn/a.chn", "FPT hợp tác cùng ACB",
                "Hai doanh nghiệp ký thỏa thuận", "Cổ phiếu FPT và ACB được nhắc trong bài.",
                null, null, "url-hash", "content-hash", null);

        List<NewsCompanyMatcher.Match> matches = matcher.match(article, matcher.loadCatalog());

        assertThat(matches).hasSize(2);
        assertThat(matches).extracting(NewsCompanyMatcher.Match::companyId)
                .containsExactlyInAnyOrder(fptCompanyId, acbCompanyId);
        assertThat(matches).extracting(NewsCompanyMatcher.Match::securityId)
                .containsExactlyInAnyOrder(fptSecurityId, acbSecurityId);
        assertThat(matches).allSatisfy(match -> {
            assertThat(match.evidence().path("field").asText()).isEqualTo("title");
            assertThat(match.evidence().path("term").asText()).isNotBlank();
            assertThat(match.score()).isPositive();
        });
    }

    @Test
    void matchesCompanyNameWithoutInventingAStockAndIgnoresInactiveMasterData() {
        CompanyJpaRepository companies = mock(CompanyJpaRepository.class);
        CompanyAliasJpaRepository aliases = mock(CompanyAliasJpaRepository.class);
        SecurityJpaRepository securities = mock(SecurityJpaRepository.class);
        UUID activeCompanyId = UUID.randomUUID();
        when(companies.findAll()).thenReturn(List.of(
                company(activeCompanyId, "ACB", "Ngân hàng Á Châu", true),
                company(UUID.randomUUID(), "XYZ", "Công ty XYZ", false)));
        when(aliases.findAll()).thenReturn(List.of());
        when(securities.findAll()).thenReturn(List.of(
                security(UUID.randomUUID(), activeCompanyId, "ACB", true)));
        NewsCompanyMatcher matcher = new NewsCompanyMatcher(companies, aliases, securities, new ObjectMapper());
        NewsArticleDraft article = new NewsArticleDraft("https://cafef.vn/a.chn",
                "Ngân hàng Á Châu công bố kế hoạch", null,
                "Thông tin từ doanh nghiệp. XYZ chỉ có trong dữ liệu công ty inactive.",
                null, null, "url-hash", "content-hash", null);

        List<NewsCompanyMatcher.Match> matches = matcher.match(article, matcher.loadCatalog());

        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().companyId()).isEqualTo(activeCompanyId);
        assertThat(matches.getFirst().securityId()).isNull();
        assertThat(matches.getFirst().evidence().path("kind").asText()).isEqualTo("company_name");
    }

    private CompanyEntity company(UUID id, String code, String name, boolean active) {
        CompanyEntity company = CompanyEntity.create(null, code, name, name, null, null, null,
                null, null, null, null, null, null, active);
        ReflectionTestUtils.setField(company, "id", id);
        return company;
    }

    private SecurityEntity security(UUID id, UUID companyId, String symbol, boolean active) {
        SecurityEntity security = SecurityEntity.create(companyId, symbol, "HOSE", "EQUITY", null,
                "VND", null, null, null, null, true, active);
        ReflectionTestUtils.setField(security, "id", id);
        return security;
    }
}
