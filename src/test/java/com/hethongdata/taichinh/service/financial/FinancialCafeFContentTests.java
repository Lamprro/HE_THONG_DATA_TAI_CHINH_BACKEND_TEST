package com.hethongdata.taichinh.service.financial;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinancialCafeFContentTests {
    private FinancialStatementBuildService.ItemDraft item(String id, String label, String value) {
        return new FinancialStatementBuildService.ItemDraft(id, label, new BigDecimal(value), value,
                null, id, label, "cafef");
    }
    @Test void bankValuesCannotBeBuiltUnderSalesLabels() {
        var rows = List.of(item("CAF_01_a", "Sales", "4571981000000"),
                item("CAF_02_b", "Deductions", "3176389000000"),
                item("CAF_10_c", "Net sales (10 = 01 - 02)", "308471000000"));
        assertThatThrownBy(() -> FinancialStatementBuildService.validateCafeFIncomeIdentity(rows))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CAFEF_INCOME_IDENTITY_MISMATCH");
    }
    @Test void validSalesIdentityAndMissingOperandsArePreserved() {
        var net = item("CAF_10_c", "Net sales (10 = 01 - 02)", "90");
        assertThatCode(() -> FinancialStatementBuildService.validateCafeFIncomeIdentity(List.of(
                item("CAF_01_a", "Sales", "100"),item("CAF_02_b", "Deductions", "10"),net))).doesNotThrowAnyException();
        assertThatCode(() -> FinancialStatementBuildService.validateCafeFIncomeIdentity(List.of(net))).doesNotThrowAnyException();
    }
}
