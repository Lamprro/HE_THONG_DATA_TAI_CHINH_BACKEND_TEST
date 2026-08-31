package com.hethongdata.taichinh.dto.master;

import jakarta.validation.constraints.NotNull;

public record UpdateActivationRequest(@NotNull Boolean active) {
}
