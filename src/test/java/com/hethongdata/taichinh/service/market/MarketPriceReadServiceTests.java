package com.hethongdata.taichinh.service.market;

import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.repository.jpa.market.MarketPriceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MarketPriceReadServiceTests {
    private final SecurityJpaRepository securities = mock(SecurityJpaRepository.class);
    private final MarketPriceJpaRepository prices = mock(MarketPriceJpaRepository.class);
    private final MarketPriceReadService service = new MarketPriceReadService(securities, prices);

    private UUID security() {
        var security = mock(SecurityEntity.class);
        UUID id = UUID.randomUUID();
        when(security.getId()).thenReturn(id);
        when(securities.findBySymbolIgnoreCase("FPT")).thenReturn(Optional.of(security));
        return id;
    }

    @Test
    void omittedIntervalPreservesExistingReadContract() {
        UUID id = security();
        when(prices.findBySecurityIdAndIsCanonicalTrueOrderByPriceTimestampDesc(id, PageRequest.of(0, 20)))
                .thenReturn(Page.empty());
        assertThat(service.canonicalPrices("FPT", 0, 20)).isEmpty();
        verify(prices).findBySecurityIdAndIsCanonicalTrueOrderByPriceTimestampDesc(id, PageRequest.of(0, 20));
        verifyNoMoreInteractions(prices);
    }

    @Test
    void snapshotAndDailyReadsUseExplicitNormalizedInterval() {
        UUID id = security();
        for (String interval : new String[]{"snapshot", "1d"}) {
            when(prices.findBySecurityIdAndIntervalCodeAndIsCanonicalTrueOrderByPriceTimestampDesc(
                    id, interval, PageRequest.of(0, 20))).thenReturn(Page.empty());
            assertThat(service.canonicalPrices("FPT", 0, 20, " " + interval.toUpperCase(java.util.Locale.ROOT) + " "))
                    .isEmpty();
            verify(prices).findBySecurityIdAndIntervalCodeAndIsCanonicalTrueOrderByPriceTimestampDesc(
                    id, interval, PageRequest.of(0, 20));
        }
        verifyNoMoreInteractions(prices);
    }

    @Test
    void unsupportedIntervalDoesNotQueryPrices() {
        security();
        assertThatThrownBy(() -> service.canonicalPrices("FPT", 0, 20, "15m"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(prices);
    }

    @Test
    void missingSecurityDoesNotQueryPrices() {
        when(securities.findBySymbolIgnoreCase("MISSING")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.canonicalPrices("MISSING", 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(prices);
    }
}
