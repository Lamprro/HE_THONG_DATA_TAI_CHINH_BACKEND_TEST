package com.hethongdata.taichinh.controller.admin;

import com.hethongdata.taichinh.entity.MarketPriceEntity;
import com.hethongdata.taichinh.service.market.MarketPriceReadService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/market-prices")
public class MarketPriceAdminController {
    private final MarketPriceReadService reads;

    public MarketPriceAdminController(MarketPriceReadService reads) {
        this.reads = reads;
    }

    @GetMapping("/{symbol}")
    public Page<MarketPriceEntity> canonicalPrices(@PathVariable String symbol,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return reads.canonicalPrices(symbol, Math.max(0, page), Math.max(1, Math.min(size, 100)));
    }
}
