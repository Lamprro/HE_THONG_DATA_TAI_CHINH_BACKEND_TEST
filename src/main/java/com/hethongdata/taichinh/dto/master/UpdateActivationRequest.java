package com.hethongdata.taichinh.dto.master;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public final class UpdateActivationRequest {

    @NotNull private Boolean active;
}
