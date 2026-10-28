package com.hethongdata.taichinh.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import com.hethongdata.taichinh.service.DatabaseHealthService;

@RestController
@RequestMapping("/api")
public class HealthController {
    @org.springframework.beans.factory.annotation.Value("${APP_BUILD_COMMIT:unknown}")
    private String buildCommit;
    private final DatabaseHealthService database;

    public HealthController(DatabaseHealthService database) { this.database = database; }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        var state = database.check();
        return ResponseEntity.status("UP".equals(state.status()) ? 200 : 503)
            .body(Map.of("status", state.status(), "service", "financial-data-api", "database", state, "buildCommit", buildCommit));
    }

    @GetMapping("/health/live")
    public ResponseEntity<Map<String, String>> live() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "financial-data-api", "buildCommit", buildCommit));
    }
}
