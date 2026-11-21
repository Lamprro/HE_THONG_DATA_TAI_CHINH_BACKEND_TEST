package com.hethongdata.taichinh.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationTests {
    @Test void missingSigningKeyCannotStartProduction() {
        assertThatThrownBy(() -> ProductionConfiguration.validate(false, "https://app.example.com"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FINANCIAL_JWT_SECRET");
    }

    @Test void wildcardCannotStartProduction() {
        assertThatThrownBy(() -> ProductionConfiguration.validate(true, "https://*.vercel.app"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void exactHttpsOriginIsAccepted() {
        ProductionConfiguration.validate(true, "https://app.example.com");
    }

    @Test void serverOnlyProductionNeedsNoCors() {
        ProductionConfiguration.validate(true, "");
    }
}
