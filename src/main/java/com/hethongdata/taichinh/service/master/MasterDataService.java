package com.hethongdata.taichinh.service.master;

import com.hethongdata.taichinh.common.AppParams;
import com.hethongdata.taichinh.dto.master.MasterDataDtos.CompanyAliasRequest;
import com.hethongdata.taichinh.dto.master.MasterDataDtos.CompanyAliasResponse;
import com.hethongdata.taichinh.dto.master.MasterDataDtos.CompanyRequest;
import com.hethongdata.taichinh.dto.master.MasterDataDtos.CompanyResponse;
import com.hethongdata.taichinh.dto.master.MasterDataDtos.SecurityRequest;
import com.hethongdata.taichinh.dto.master.MasterDataDtos.SecurityResponse;
import com.hethongdata.taichinh.entity.master.CompanyAliasEntity;
import com.hethongdata.taichinh.entity.master.CompanyEntity;
import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.repository.jpa.master.CompanyAliasJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.CompanyJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Application service for admin-managed company/security masters and their job lifecycle. */
@Service
public class MasterDataService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MasterDataService.class);

    private final CompanyJpaRepository companies;
    private final CompanyAliasJpaRepository aliases;
    private final SecurityJpaRepository securities;
    private final SecurityJobProvisioningService jobProvisioning;

    public MasterDataService(
            CompanyJpaRepository companies,
            CompanyAliasJpaRepository aliases,
            SecurityJpaRepository securities,
            SecurityJobProvisioningService jobProvisioning) {
        this.companies = companies;
        this.aliases = aliases;
        this.securities = securities;
        this.jobProvisioning = jobProvisioning;
    }

    @Transactional
    public CompanyResponse createCompany(CompanyRequest request) {
        String code = AppParams.requiredUpper(request.companyCode(), "companyCode");
        if (companies.findByCompanyCodeIgnoreCase(code).isPresent())
            throw new IllegalArgumentException("companyCode already exists: " + code);
        CompanyEntity entity =
                companies.save(
                        CompanyEntity.create(
                                normalize(request.taxCode()),
                                code,
                                AppParams.requiredTrimmed(request.legalName(), "legalName"),
                                normalize(request.shortName()),
                                normalize(request.englishName()),
                                normalize(request.industryCode()),
                                normalize(request.industryName()),
                                normalize(request.sectorName()),
                                normalize(request.website()),
                                normalize(request.headquarters()),
                                request.foundedDate(),
                                normalize(request.listingStatus()),
                                normalize(request.description()),
                                request.active() == null || request.active()));
        LOGGER.info("Created new company: code={}, name={}, id={}", code, entity.getLegalName(), entity.getId());
        return CompanyResponse.from(entity);
    }

    @Transactional
    public CompanyResponse updateCompany(UUID companyId, CompanyRequest request) {
        CompanyEntity company = company(companyId);
        company.update(
                normalize(request.taxCode()),
                AppParams.requiredUpper(request.companyCode(), "companyCode"),
                AppParams.requiredTrimmed(request.legalName(), "legalName"),
                normalize(request.shortName()),
                normalize(request.englishName()),
                normalize(request.industryCode()),
                normalize(request.industryName()),
                normalize(request.sectorName()),
                normalize(request.website()),
                normalize(request.headquarters()),
                request.foundedDate(),
                normalize(request.listingStatus()),
                normalize(request.description()),
                request.active() == null || request.active());
        CompanyEntity updated = companies.save(company);
        LOGGER.info("Updated company: id={}, code={}", companyId, updated.getCompanyCode());
        return CompanyResponse.from(updated);
    }

    @Transactional(readOnly = true)
    public List<CompanyResponse> companies() {
        return companies.findAll().stream().map(CompanyResponse::from).toList();
    }

    @Transactional
    public CompanyAliasResponse addAlias(UUID companyId, CompanyAliasRequest request) {
        company(companyId);
        String alias = AppParams.requiredTrimmed(request.alias(), "alias");
        if (aliases.existsByCompanyIdAndAliasIgnoreCase(companyId, alias))
            throw new IllegalArgumentException("alias already exists for company");
        String aliasType = AppParams.requiredUpper(request.aliasType(), "aliasType");
        if (!AppParams.COMPANY_ALIAS_TYPES.contains(aliasType)) {
            throw new IllegalArgumentException("Unsupported aliasType: " + aliasType);
        }
        CompanyAliasEntity saved = aliases.save(CompanyAliasEntity.create(companyId, alias, aliasType));
        LOGGER.info("Added alias for company {}: alias={}, type={}", companyId, alias, aliasType);
        return CompanyAliasResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<CompanyAliasResponse> aliases(UUID companyId) {
        company(companyId);
        return aliases.findByCompanyIdOrderByAliasAsc(companyId).stream().map(CompanyAliasResponse::from).toList();
    }

    @Transactional
    public SecurityResponse createSecurity(SecurityRequest request) {
        company(request.companyId());
        String symbol = AppParams.requiredUpper(request.symbol(), "symbol");
        if (securities.findBySymbolIgnoreCase(symbol).isPresent())
            throw new IllegalArgumentException("symbol already exists: " + symbol);
        String exchange = AppParams.requiredUpper(request.exchange(), "exchange");
        String securityType = AppParams.requiredUpper(request.securityType(), "securityType");
        validateSecurityClassification(exchange, securityType);
        SecurityEntity security =
                securities.save(
                        SecurityEntity.create(
                                request.companyId(),
                                symbol,
                                exchange,
                                securityType,
                                normalize(request.isin()),
                                AppParams.requiredUpper(request.currency(), "currency"),
                                request.listedDate(),
                                request.delistedDate(),
                                request.sharesOutstanding(),
                                request.parValue(),
                                request.primary() == null || request.primary(),
                                request.active() == null || request.active()));
        int provisionedJobs = jobProvisioning.provision(security);
        LOGGER.info("Created security: symbol={}, exchange={}, type={}, provisionedJobs={}", symbol, exchange, securityType, provisionedJobs);
        return SecurityResponse.from(security);
    }

    @Transactional
    public SecurityResponse updateSecurity(UUID securityId, SecurityRequest request) {
        company(request.companyId());
        SecurityEntity security = security(securityId);
        String exchange = AppParams.requiredUpper(request.exchange(), "exchange");
        String securityType = AppParams.requiredUpper(request.securityType(), "securityType");
        validateSecurityClassification(exchange, securityType);
        security.update(
                request.companyId(),
                AppParams.requiredUpper(request.symbol(), "symbol"),
                exchange,
                securityType,
                normalize(request.isin()),
                AppParams.requiredUpper(request.currency(), "currency"),
                request.listedDate(),
                request.delistedDate(),
                request.sharesOutstanding(),
                request.parValue(),
                request.primary() == null || request.primary(),
                request.active() == null || request.active());
        securities.save(security);
        int provisionedJobs = jobProvisioning.provision(security);
        LOGGER.info("Updated security: id={}, symbol={}, provisionedJobs={}", securityId, security.getSymbol(), provisionedJobs);
        return SecurityResponse.from(security);
    }

    @Transactional
    public SecurityResponse setSecurityActive(UUID securityId, boolean active) {
        SecurityEntity security = security(securityId);
        security.setActive(active);
        securities.save(security);
        int changedJobs = jobProvisioning.provision(security);
        LOGGER.info("Updated security active state: id={}, symbol={}, active={}, affectedJobs={}", securityId, security.getSymbol(), active, changedJobs);
        return SecurityResponse.from(security);
    }

    @Transactional
    public int provisionSecurity(UUID securityId) {
        LOGGER.info("Manually provisioning jobs for security id: {}", securityId);
        int count = jobProvisioning.provision(security(securityId));
        LOGGER.info("Provisioned {} jobs for security id: {}", count, securityId);
        return count;
    }

    @Transactional
    public int reconcileActiveSecurities() {
        LOGGER.info("Starting reconciliation of all active securities");
        int count = securities.findByIsActiveTrueOrderBySymbolAsc().stream()
                .mapToInt(jobProvisioning::provision)
                .sum();
        LOGGER.info("Reconciliation completed: synchronized {} jobs across active securities", count);
        return count;
    }

    @Transactional(readOnly = true)
    public List<SecurityResponse> securities(boolean activeOnly) {
        List<SecurityEntity> result =
                activeOnly ? securities.findByIsActiveTrueOrderBySymbolAsc() : securities.findAll();
        return result.stream().map(SecurityResponse::from).toList();
    }

    private CompanyEntity company(UUID id) {
        return companies
                .findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + id));
    }

    private SecurityEntity security(UUID id) {
        return securities
                .findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Security not found: " + id));
    }

    private static void validateSecurityClassification(String exchange, String securityType) {
        if (!AppParams.SECURITY_EXCHANGES.contains(exchange))
            throw new IllegalArgumentException("Unsupported exchange: " + exchange);
        if (!AppParams.SECURITY_TYPES.contains(securityType))
            throw new IllegalArgumentException("Unsupported securityType: " + securityType);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
