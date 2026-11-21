package com.hethongdata.taichinh.config;

import com.hethongdata.taichinh.config.SecurityConfiguration.AdminCredentials;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationTests {
    private final AdminCredentials shared = new AdminCredentials("a".repeat(40), Map.of());

    @Test
    void missingCredentialCannotStartProduction() {
        var missing = new AdminCredentials("", Map.of());
        assertThatThrownBy(() -> ProductionConfiguration.validate(missing, "https://app.example.com"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void namedAdminCanStartProductionAndAuditorAloneCannot() {
        var admin = new AdminCredentials.Credential("b".repeat(40), null, Set.of("ADMIN"));
        var auditor = new AdminCredentials.Credential("c".repeat(40), null, Set.of("AUDITOR"));
        ProductionConfiguration.validate(new AdminCredentials("", Map.of("ops", admin)), "");
        assertThatThrownBy(() -> ProductionConfiguration.validate(new AdminCredentials("", Map.of("audit", auditor)), ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wildcardCannotStartProduction() {
        assertThatThrownBy(() -> ProductionConfiguration.validate(shared, "https://*.vercel.app"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exactHttpsOriginIsAccepted() {
        ProductionConfiguration.validate(shared, "https://app.example.com");
    }

    @Test
    void serverOnlyProductionNeedsNoCors() {
        ProductionConfiguration.validate(shared, "");
    }
}
