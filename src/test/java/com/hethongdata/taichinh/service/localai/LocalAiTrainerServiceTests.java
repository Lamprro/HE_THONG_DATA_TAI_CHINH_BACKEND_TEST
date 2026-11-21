package com.hethongdata.taichinh.service.localai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.repository.jdbc.localai.LocalAiTrainingQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalAiTrainerServiceTests {
    private JdbcTemplate db;
    private ObjectMapper json;
    private LocalAiTrainerService service;

    @BeforeEach
    void setup() {
        db = mock(JdbcTemplate.class);
        json = new ObjectMapper();
        var repo = new LocalAiTrainingQueryRepository(db);
        service = new LocalAiTrainerService(repo, json);
    }

    @Test
    void getReadinessReturnsExpectedStructure() {
        when(db.queryForObject(contains("securities"), eq(Integer.class))).thenReturn(105);
        when(db.queryForObject(contains("DISTINCT security_id"), eq(Integer.class))).thenReturn(103);
        when(db.queryForObject(contains("price_date >= '2021-01-01'"), eq(Integer.class))).thenReturn(100);
        when(db.queryForObject(contains("AND open_price BETWEEN"), eq(Integer.class))).thenReturn(0);
        when(db.queryForObject(contains("max(price_date)"), eq(String.class))).thenReturn("2026-11-19");
        when(db.queryForObject(contains("max(p.end_date)"), eq(String.class))).thenReturn("2026-09-30");
        when(db.queryForObject(contains("index_prices"), eq(Integer.class), anyString())).thenReturn(2185);

        Map<String, Object> report = service.getReadiness();
        assertNotNull(report);
        assertEquals(Boolean.TRUE, report.get("ready_to_train"));
        assertEquals(100, report.get("eligible_stocks"));
        assertEquals(0, report.get("stock_ohlc_failures"));
        assertNotNull(report.get("readiness_sha256"));
        assertTrue(report.containsKey("disclaimer"));
    }

    @Test
    void retrainRejectsBlankReason() {
        assertThrows(IllegalArgumentException.class, () -> service.retrain(UUID.randomUUID(), "MANUAL", "admin", "  ", 1));
    }

    @Test
    void retrainRespectsIdempotency() {
        UUID key = UUID.randomUUID();
        when(db.queryForList(contains("WHERE idempotency_key = ?"), eq(key)))
            .thenReturn(List.of(Map.of("id", UUID.randomUUID(), "status", "RUNNING", "selected_model", "FACTOR_COMPOSITE")));

        Map<String, Object> result = service.retrain(key, "MANUAL", "admin", "Daily routine", 1);
        assertNotNull(result);
        assertEquals("RUNNING", result.get("status"));
    }

    @Test
    void readyDataCannotTriggerAnUnverifiedTrainerOrPublish() {
        when(db.queryForObject(contains("price_date >= '2021-01-01'"), eq(Integer.class))).thenReturn(100);
        when(db.queryForObject(contains("index_prices"), eq(Integer.class), anyString())).thenReturn(2000);
        assertEquals(503, assertThrows(ResponseStatusException.class,
            () -> service.retrain(UUID.randomUUID(), "MANUAL", "admin", "Run verified trainer", 1)).getStatusCode().value());
        assertEquals(503, assertThrows(ResponseStatusException.class,
            () -> service.publish(UUID.randomUUID(), "V1", "Publish")).getStatusCode().value());
        assertEquals(503, assertThrows(ResponseStatusException.class,
            () -> service.rollback("Rollback")).getStatusCode().value());
    }

    @Test
    void blockedRunBindsTheReasonInTheCorrectPosition() {
        var repo = new LocalAiTrainingQueryRepository(db);
        UUID run = UUID.randomUUID(), key = UUID.randomUUID();
        repo.insertBlockedRun(run, "MANUAL", "Check data", "admin", key, "digest", "rank-v3");
        verify(db).update(contains("'BLOCKED'"), eq(run), eq("MANUAL"), eq("Check data"),
            eq("admin"), eq(key), eq("digest"), eq("rank-v3"));
    }
}
