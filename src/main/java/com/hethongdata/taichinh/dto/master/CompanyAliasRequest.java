package com.hethongdata.taichinh.dto.master;

import jakarta.validation.constraints.NotBlank;

public record CompanyAliasRequest(@NotBlank String alias, @NotBlank String aliasType) {
}
