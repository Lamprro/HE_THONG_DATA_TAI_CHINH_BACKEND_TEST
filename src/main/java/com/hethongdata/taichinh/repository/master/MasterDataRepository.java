package com.hethongdata.taichinh.repository.master;

import com.hethongdata.taichinh.entity.master.CompanyAliasEntity;
import com.hethongdata.taichinh.entity.master.CompanyEntity;
import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.repository.jpa.master.CompanyAliasJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.CompanyJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Database boundary for master data. Services own all workflow and job decisions. */
@Repository
public class MasterDataRepository {
    private final CompanyJpaRepository companies;
    private final CompanyAliasJpaRepository aliases;
    private final SecurityJpaRepository securities;

    public MasterDataRepository(
            CompanyJpaRepository companies,
            CompanyAliasJpaRepository aliases,
            SecurityJpaRepository securities) {
        this.companies = companies;
        this.aliases = aliases;
        this.securities = securities;
    }

    public CompanyEntity saveCompany(CompanyEntity company) {

        return companies.save(company);
    }

    public Optional<CompanyEntity> findCompany(UUID id) {
        return companies.findById(id);
    }

    public Optional<CompanyEntity> findCompanyByCode(String code) {
        return companies.findByCompanyCodeIgnoreCase(code);
    }

    public List<CompanyEntity> findCompanies() {
        return companies.findAll();
    }

    public CompanyAliasEntity saveAlias(CompanyAliasEntity alias) {
        return aliases.save(alias);
    }

    public List<CompanyAliasEntity> findAliases(UUID companyId) {
        return aliases.findByCompanyIdOrderByAliasAsc(companyId);
    }

    public boolean aliasExists(UUID companyId, String alias) {
        return aliases.existsByCompanyIdAndAliasIgnoreCase(companyId, alias);
    }

    public SecurityEntity saveSecurity(SecurityEntity security) {
        return securities.save(security);
    }

    public Optional<SecurityEntity> findSecurity(UUID id) {
        return securities.findById(id);
    }

    public Optional<SecurityEntity> findSecurityBySymbol(String symbol) {
        return securities.findBySymbolIgnoreCase(symbol);
    }

    public List<SecurityEntity> findActiveSecurities() {
        return securities.findByIsActiveTrueOrderBySymbolAsc();
    }

    public List<SecurityEntity> findSecurities() {
        return securities.findAll();
    }
}
