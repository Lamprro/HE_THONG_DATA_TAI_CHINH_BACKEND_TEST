package com.hethongdata.taichinh.service.market;

import com.hethongdata.taichinh.entity.MarketPriceEntity;
import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.repository.jpa.market.MarketPriceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarketPriceReadService {
    private final SecurityJpaRepository securities;
    private final MarketPriceJpaRepository prices;

    public MarketPriceReadService(SecurityJpaRepository securities,
            MarketPriceJpaRepository prices) {
        this.securities = securities;
        this.prices = prices;
    }

    @Transactional(readOnly = true)
    public Page<MarketPriceEntity> canonicalPrices(String symbol, int page, int size) {
        return canonicalPrices(symbol, page, size, null);
    }

    @Transactional(readOnly = true)
    public Page<MarketPriceEntity> canonicalPrices(String symbol, int page, int size, String interval) {
        SecurityEntity security = securities.findBySymbolIgnoreCase(symbol)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Không tìm thấy mã chứng khoán: " + symbol));
        if (interval != null && !interval.isBlank()) {
            String normalized = interval.trim().toLowerCase(java.util.Locale.ROOT);
            if (!"1d".equals(normalized) && !"snapshot".equals(normalized)) {
                throw new IllegalArgumentException("interval phải là 1d hoặc snapshot");
            }
            return prices.findBySecurityIdAndIntervalCodeAndIsCanonicalTrueOrderByPriceTimestampDesc(
                    security.getId(), normalized, PageRequest.of(page, size));
        }
        return prices.findBySecurityIdAndIsCanonicalTrueOrderByPriceTimestampDesc(
                security.getId(), PageRequest.of(page, size));
    }
}
