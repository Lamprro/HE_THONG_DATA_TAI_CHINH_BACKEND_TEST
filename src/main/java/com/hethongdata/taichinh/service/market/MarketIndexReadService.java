package com.hethongdata.taichinh.service.market;

import com.hethongdata.taichinh.entity.IndexPriceEntity;
import com.hethongdata.taichinh.entity.MarketIndexEntity;
import com.hethongdata.taichinh.entity.SecurityIndexMembershipEntity;
import com.hethongdata.taichinh.repository.jpa.market.IndexPriceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.market.MarketIndexJpaRepository;
import com.hethongdata.taichinh.repository.jpa.market.SecurityIndexMembershipJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarketIndexReadService {
    private final MarketIndexJpaRepository indices;
    private final IndexPriceJpaRepository prices;
    private final SecurityIndexMembershipJpaRepository memberships;

    public MarketIndexReadService(MarketIndexJpaRepository indices,
            IndexPriceJpaRepository prices, SecurityIndexMembershipJpaRepository memberships) {
        this.indices = indices;
        this.prices = prices;
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public Page<MarketIndexEntity> indices(int page, int size) {
        return indices.findAll(PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public Page<IndexPriceEntity> canonicalPrices(String code, int page, int size) {
        return prices.findByMarketIndexIdAndIsCanonicalTrueOrderByPriceTimestampDesc(
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
