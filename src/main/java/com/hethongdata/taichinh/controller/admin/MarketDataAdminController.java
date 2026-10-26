package com.hethongdata.taichinh.controller.admin;

import com.hethongdata.taichinh.entity.*;
import com.hethongdata.taichinh.service.market.*;
import com.hethongdata.taichinh.service.macro.MacroJobCatalogService;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Admin entry points for stored market data and native market catalogs. */
@RestController
@RequestMapping("/api/admin")
public class MarketDataAdminController {
    private final MarketPriceReadService prices;
    private final MarketIndexReadService indices;
    private final IndexJobProvisioningService indexJobs;
    private final MacroJobCatalogService macroJobs;
    private final JdbcTemplate db;

    public MarketDataAdminController(MarketPriceReadService prices, MarketIndexReadService indices,
            IndexJobProvisioningService indexJobs, MacroJobCatalogService macroJobs, JdbcTemplate db) {
        this.prices = prices;
        this.indices = indices;
        this.indexJobs = indexJobs;
        this.macroJobs = macroJobs;
        this.db = db;
    }

    @GetMapping("/market-prices/{symbol}")
    public Page<MarketPriceEntity> canonicalPrices(@PathVariable String symbol,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String interval) {
        return prices.canonicalPrices(symbol, safePage(page), safeSize(size), interval);
    }

    @PostMapping("/market-indices/catalog/seed")
    public ResponseEntity<Map<String, Integer>> seed() {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("jobs", indexJobs.seed()));
    }

    @GetMapping("/market-indices")
    public Page<MarketIndexEntity> indices(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return indices.indices(safePage(page), safeSize(size));
    }

    @GetMapping("/market-indices/{code}/prices")
    public Page<IndexPriceEntity> canonicalPrices(@PathVariable String code,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return indices.canonicalPrices(code, safePage(page), safeSize(size));
    }

    @GetMapping("/market-indices/{code}/memberships")
    public Page<SecurityIndexMembershipEntity> memberships(@PathVariable String code,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return indices.memberships(code, safePage(page), safeSize(size));
    }

    @PostMapping("/macro/jobs/seed")
    public Map<String, Object> seedMacroJobs() { return macroJobs.seed(); }

    @GetMapping("/macro/coverage")
    public List<Map<String, Object>> coverage() {
        return db.queryForList(
                "SELECT s.code,s.name,s.country_code,s.frequency,s.unit,count(o.id)"
                    + " observation_count,min(o.observation_date)"
                    + " first_period_end,max(o.observation_date) last_period_end FROM macro_series"
                    + " s LEFT JOIN macro_observations o ON o.macro_series_id=s.id WHERE"
                    + " s.country_code='VNM' GROUP BY s.id ORDER BY s.code");
    }

    private static int safePage(int page) { return Math.max(0, page); }
    private static int safeSize(int size) { return Math.max(1, Math.min(size, 100)); }
}
