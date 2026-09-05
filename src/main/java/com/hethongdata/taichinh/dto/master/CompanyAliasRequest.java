package com.hethongdata.taichinh.dto.master;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public final class CompanyAliasRequest {

    @NotBlank private String alias;

    @NotBlank private String aliasType;
}
