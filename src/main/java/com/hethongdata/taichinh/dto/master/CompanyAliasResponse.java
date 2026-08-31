package com.hethongdata.taichinh.dto.master;

import com.hethongdata.taichinh.entity.master.CompanyAliasEntity;

public record CompanyAliasResponse(Long id, String alias, String aliasType) {
    public static CompanyAliasResponse from(CompanyAliasEntity value) {
        return new CompanyAliasResponse(value.getId(), value.getAlias(), value.getAliasType());
    }
}
