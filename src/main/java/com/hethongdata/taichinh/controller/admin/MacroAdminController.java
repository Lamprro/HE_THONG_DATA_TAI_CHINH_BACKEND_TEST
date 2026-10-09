package com.hethongdata.taichinh.controller.admin;

import com.hethongdata.taichinh.service.macro.MacroJobCatalogService;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/admin/macro")
public class MacroAdminController {
    private final MacroJobCatalogService catalog;
    private final JdbcTemplate db;

    public MacroAdminController(MacroJobCatalogService catalog, JdbcTemplate db) {
        this.catalog = catalog;
        this.db = db;
    }

    @PostMapping("/jobs/seed")
    public Map<String, Object> seed() {
        return catalog.seed();
    }

    @GetMapping("/coverage")
    public List<Map<String, Object>> coverage() {
        return db.queryForList(
                "SELECT s.code,s.name,s.country_code,s.frequency,s.unit,count(o.id)"
                    + " observation_count,min(o.observation_date)"
                    + " first_period_end,max(o.observation_date) last_period_end FROM macro_series"
                    + " s LEFT JOIN macro_observations o ON o.macro_series_id=s.id WHERE"
                    + " s.country_code='VNM' GROUP BY s.id ORDER BY s.code");
    }
}
