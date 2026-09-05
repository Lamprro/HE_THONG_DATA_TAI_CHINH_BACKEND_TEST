package com.hethongdata.taichinh.service.master;

import com.hethongdata.taichinh.common.AppParams;
import com.hethongdata.taichinh.dto.master.CompanyAliasRequest;
import com.hethongdata.taichinh.dto.master.CompanyAliasResponse;
import com.hethongdata.taichinh.dto.master.CompanyRequest;
import com.hethongdata.taichinh.dto.master.CompanyResponse;
import com.hethongdata.taichinh.dto.master.SecurityRequest;
import com.hethongdata.taichinh.dto.master.SecurityResponse;
import com.hethongdata.taichinh.entity.master.CompanyAliasEntity;
import com.hethongdata.taichinh.entity.master.CompanyEntity;
import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.repository.master.MasterDataRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Application service for admin-managed company/security masters and their job lifecycle. */
@Service
public class MasterDataService {
    private final MasterDataRepository masterData;
    private final SecurityJobProvisioningService jobProvisioning;

    public MasterDataService(
            MasterDataRepository masterData, SecurityJobProvisioningService jobProvisioning) {
        this.masterData = masterData;
        this.jobProvisioning = jobProvisioning;
    }

    @Transactional
    public CompanyResponse createCompany(CompanyRequest request) {
        String code = AppParams.requiredUpper(request.getCompanyCode(), "companyCode");
        if (masterData.findCompanyByCode(code).isPresent())
            throw new IllegalArgumentException("companyCode already exists: " + code);
        return CompanyResponse.from(
                masterData.saveCompany(
                        CompanyEntity.create(
                                normalize(request.getTaxCode()),
                                code,
                                AppParams.requiredTrimmed(request.getLegalName(), "legalName"),
                                normalize(request.getShortName()),
                                normalize(request.getEnglishName()),
                                normalize(request.getIndustryCode()),
                                normalize(request.getIndustryName()),
                                normalize(request.getSectorName()),
                                normalize(request.getWebsite()),
                                normalize(request.getHeadquarters()),
                                request.getFoundedDate(),
                                normalize(request.getListingStatus()),
                                normalize(request.getDescription()),
                                request.getActive() == null || request.getActive())));
    }

    @Transactional
    public CompanyResponse updateCompany(UUID companyId, CompanyRequest request) {
        CompanyEntity company = company(companyId);
        company.update(
                normalize(request.getTaxCode()),
                AppParams.requiredUpper(request.getCompanyCode(), "companyCode"),
                AppParams.requiredTrimmed(request.getLegalName(), "legalName"),
                normalize(request.getShortName()),
                normalize(request.getEnglishName()),
                normalize(request.getIndustryCode()),
                normalize(request.getIndustryName()),
                normalize(request.getSectorName()),
                normalize(request.getWebsite()),
                normalize(request.getHeadquarters()),
                request.getFoundedDate(),
                normalize(request.getListingStatus()),
                normalize(request.getDescription()),
                request.getActive() == null || request.getActive());
        return CompanyResponse.from(masterData.saveCompany(company));
    }

    @Transactional(readOnly = true)
    public List<CompanyResponse> companies() {
        return masterData.findCompanies().stream().map(CompanyResponse::from).toList();
    }

    @Transactional
    public CompanyAliasResponse addAlias(UUID companyId, CompanyAliasRequest request) {
        company(companyId);
        String alias = AppParams.requiredTrimmed(request.getAlias(), "alias");
        if (masterData.aliasExists(companyId, alias))
            throw new IllegalArgumentException("alias already exists for company");
        String aliasType = AppParams.requiredUpper(request.getAliasType(), "aliasType");
        if (!AppParams.COMPANY_ALIAS_TYPES.contains(aliasType)) {
            throw new IllegalArgumentException("Unsupported aliasType: " + aliasType);
        }
        return CompanyAliasResponse.from(
                masterData.saveAlias(CompanyAliasEntity.create(companyId, alias, aliasType)));
    }

    @Transactional(readOnly = true)
    public List<CompanyAliasResponse> aliases(UUID companyId) {
        company(companyId);
        return masterData.findAliases(companyId).stream().map(CompanyAliasResponse::from).toList();
    }

    @Transactional
    public SecurityResponse createSecurity(SecurityRequest request) {
        company(request.getCompanyId());
        String symbol = AppParams.requiredUpper(request.getSymbol(), "symbol");
        if (masterData.findSecurityBySymbol(symbol).isPresent())
            throw new IllegalArgumentException("symbol already exists: " + symbol);
        String exchange = AppParams.requiredUpper(request.getExchange(), "exchange");
        String securityType = AppParams.requiredUpper(request.getSecurityType(), "securityType");
        validateSecurityClassification(exchange, securityType);
        SecurityEntity security =
                masterData.saveSecurity(
                        SecurityEntity.create(
                                request.getCompanyId(),
                                symbol,
                                exchange,
                                securityType,
                                normalize(request.getIsin()),
                                AppParams.requiredUpper(request.getCurrency(), "currency"),
                                request.getListedDate(),
                                request.getDelistedDate(),
                                request.getSharesOutstanding(),
                                request.getParValue(),
                                request.getPrimary() == null || request.getPrimary(),
                                request.getActive() == null || request.getActive()));
        jobProvisioning.provision(security);
        return SecurityResponse.from(security);
    }

    @Transactional
    public SecurityResponse updateSecurity(UUID securityId, SecurityRequest request) {
        company(request.getCompanyId());
        SecurityEntity security = security(securityId);
        String exchange = AppParams.requiredUpper(request.getExchange(), "exchange");
        String securityType = AppParams.requiredUpper(request.getSecurityType(), "securityType");
        validateSecurityClassification(exchange, securityType);
        security.update(
                request.getCompanyId(),
                AppParams.requiredUpper(request.getSymbol(), "symbol"),
                exchange,
                securityType,
                normalize(request.getIsin()),
                AppParams.requiredUpper(request.getCurrency(), "currency"),
                request.getListedDate(),
                request.getDelistedDate(),
                request.getSharesOutstanding(),
                request.getParValue(),
                request.getPrimary() == null || request.getPrimary(),
                request.getActive() == null || request.getActive());
        masterData.saveSecurity(security);
        jobProvisioning.provision(security);
        return SecurityResponse.from(security);
    }

    @Transactional
    public SecurityResponse setSecurityActive(UUID securityId, boolean active) {
        SecurityEntity security = security(securityId);
        security.setActive(active);
        masterData.saveSecurity(security);
        jobProvisioning.provision(security);
        return SecurityResponse.from(security);
    }

    @Transactional
    public int provisionSecurity(UUID securityId) {
        return jobProvisioning.provision(security(securityId));
    }

    @Transactional
    public int reconcileActiveSecurities() {
        return masterData.findActiveSecurities().stream()
                .mapToInt(jobProvisioning::provision)
                .sum();
    }

    @Transactional(readOnly = true)
    public List<SecurityResponse> securities(boolean activeOnly) {
        List<SecurityEntity> result =
                activeOnly ? masterData.findActiveSecurities() : masterData.findSecurities();
        return result.stream().map(SecurityResponse::from).toList();
    }

    private CompanyEntity company(UUID id) {
        return masterData
                .findCompany(id)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + id));
    }

    private SecurityEntity security(UUID id) {
        return masterData
                .findSecurity(id)
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
