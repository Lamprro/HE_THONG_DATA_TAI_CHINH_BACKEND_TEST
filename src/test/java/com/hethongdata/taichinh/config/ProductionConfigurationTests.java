package com.hethongdata.taichinh.config;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ProductionConfigurationTests {
 @Test void missingCredentialCannotStartProduction(){assertThatThrownBy(()->ProductionConfiguration.validate("","https://app.example.com")).isInstanceOf(IllegalStateException.class);}
 @Test void wildcardCannotStartProduction(){assertThatThrownBy(()->ProductionConfiguration.validate("a".repeat(40),"https://*.vercel.app")).isInstanceOf(IllegalStateException.class);}
 @Test void exactHttpsOriginIsAccepted(){ProductionConfiguration.validate("a".repeat(40),"https://app.example.com");}
 @Test void serverOnlyProductionNeedsNoCors(){ProductionConfiguration.validate("a".repeat(40),"");}
}
