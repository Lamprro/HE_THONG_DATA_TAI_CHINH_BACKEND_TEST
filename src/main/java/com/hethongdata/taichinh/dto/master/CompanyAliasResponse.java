package com.hethongdata.taichinh.dto.master;

import com.hethongdata.taichinh.entity.master.CompanyAliasEntity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public final class CompanyAliasResponse {

    private final Long id;

    private final String alias;

    private final String aliasType;

    public static CompanyAliasResponse from(CompanyAliasEntity value) {
        return new CompanyAliasResponse(value.getId(), value.getAlias(), value.getAliasType());
    }
}
