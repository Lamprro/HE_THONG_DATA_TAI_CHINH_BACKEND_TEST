package com.hethongdata.taichinh.service.financial;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hethongdata.taichinh.controller.admin.HistoricalFinancialMetricAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
class HistoricalFinancialMetricAdminAccessTests {
    @Test void deniesMissingAndWrongCredentialsBeforeAnyDatabaseWork() {
        var service=mock(HistoricalFinancialMetricService.class);
        var controller=new HistoricalFinancialMetricAdminController(service,"isolated-test-admin-token-at-least-32-chars");
        assertThatThrownBy(()->controller.calculate(null,null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.calculate("Bearer wrong",null)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }
    @Test void failsClosedWhenDedicatedTokenIsNotConfigured() {
        var service=mock(HistoricalFinancialMetricService.class);
        assertThatThrownBy(()->new HistoricalFinancialMetricAdminController(service,"").calculate("Bearer wrong",null)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }
}
