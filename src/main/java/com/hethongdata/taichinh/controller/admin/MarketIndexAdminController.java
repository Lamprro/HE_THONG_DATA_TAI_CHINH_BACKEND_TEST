package com.hethongdata.taichinh.controller.admin;

import com.hethongdata.taichinh.entity.IndexPriceEntity;
import com.hethongdata.taichinh.entity.MarketIndexEntity;
import com.hethongdata.taichinh.entity.SecurityIndexMembershipEntity;
import com.hethongdata.taichinh.service.market.IndexJobProvisioningService;
import com.hethongdata.taichinh.service.market.MarketIndexReadService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/market-indices")
public class MarketIndexAdminController {
    private final IndexJobProvisioningService provisioning;
    private final MarketIndexReadService reads;

    public MarketIndexAdminController(IndexJobProvisioningService provisioning,
            MarketIndexReadService reads) {
        this.provisioning = provisioning;
        this.reads = reads;
    }

    @PostMapping("/catalog/seed")
    public ResponseEntity<Map<String, Integer>> seed() {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("jobs", provisioning.seed()));
    }

    @GetMapping
    public Page<MarketIndexEntity> indices(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return reads.indices(safePage(page), safeSize(size));
    }

    @GetMapping("/{code}/prices")
    public Page<IndexPriceEntity> canonicalPrices(@PathVariable String code,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return reads.canonicalPrices(code, safePage(page), safeSize(size));
    }

    @GetMapping("/{code}/memberships")
    public Page<SecurityIndexMembershipEntity> memberships(@PathVariable String code,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return reads.memberships(code, safePage(page), safeSize(size));
    }

    private int safePage(int page) { return Math.max(0, page); }
    private int safeSize(int size) { return Math.max(1, Math.min(size, 100)); }
}
