package com.hethongdata.taichinh.service.market;

import com.hethongdata.taichinh.entity.IndexPriceEntity;
import com.hethongdata.taichinh.entity.MarketIndexEntity;
import com.hethongdata.taichinh.entity.MarketPriceEntity;
import com.hethongdata.taichinh.entity.SecurityIndexMembershipEntity;
import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.repository.jpa.market.IndexPriceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.market.MarketIndexJpaRepository;
import com.hethongdata.taichinh.repository.jpa.market.MarketPriceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.market.SecurityIndexMembershipJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only queries over canonical security prices, market indices and index memberships. */
@Service
public class MarketDataReadService {
    private final SecurityJpaRepository securities;
    private final MarketPriceJpaRepository prices;
    private final MarketIndexJpaRepository indices;
    private final IndexPriceJpaRepository indexPrices;
    private final SecurityIndexMembershipJpaRepository memberships;

    public MarketDataReadService(SecurityJpaRepository securities, MarketPriceJpaRepository prices,
            MarketIndexJpaRepository indices, IndexPriceJpaRepository indexPrices,
            SecurityIndexMembershipJpaRepository memberships) {
        this.securities = securities;
        this.prices = prices;
        this.indices = indices;
        this.indexPrices = indexPrices;
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public Page<MarketPriceEntity> securityPrices(String symbol, int page, int size) {
        return securityPrices(symbol, page, size, null);
    }

    @Transactional(readOnly = true)
    public Page<MarketPriceEntity> securityPrices(String symbol, int page, int size, String interval) {
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

    @Transactional(readOnly = true)
    public Page<MarketIndexEntity> indices(int page, int size) {
        return indices.findAll(PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public Page<IndexPriceEntity> indexPrices(String code, int page, int size) {
        return indexPrices.findByMarketIndexIdAndIsCanonicalTrueOrderByPriceTimestampDesc(
                index(code).getId(), PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public Page<SecurityIndexMembershipEntity> memberships(String code, int page, int size) {
        return memberships.findByMarketIndexIdOrderByEffectiveFromDesc(
                index(code).getId(), PageRequest.of(page, size));
    }

    private MarketIndexEntity index(String code) {
        return indices.findByCodeIgnoreCase(code)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy mã chỉ số: " + code));
    }
}
