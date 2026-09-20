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
        SecurityEntity security = securities.findBySymbolIgnoreCase(symbol)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Không tìm thấy mã chứng khoán: " + symbol));
        return prices.findBySecurityIdAndIsCanonicalTrueOrderByPriceTimestampDesc(
                security.getId(), PageRequest.of(page, size));
    }
}
