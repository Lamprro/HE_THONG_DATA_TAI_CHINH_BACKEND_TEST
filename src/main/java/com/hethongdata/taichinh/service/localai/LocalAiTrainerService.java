package com.hethongdata.taichinh.service.localai;

import com.fasterxml.jackson.databind.*;
import com.hethongdata.taichinh.repository.jdbc.localai.LocalAiTrainingQueryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class LocalAiTrainerService {
    private static final Logger LOG = LoggerFactory.getLogger(LocalAiTrainerService.class);
    private static final String PROTOCOL_VERSION = "rank-v3";
    private static final String RESEARCH_DISCLAIMER = "Mô hình nghiên cứu tham khảo (RESEARCH_ONLY), chưa kiểm chứng trong tương lai, không phải khuyến nghị đầu tư.";

    private final LocalAiTrainingQueryRepository repo;
    private final ObjectMapper json;
    public LocalAiTrainerService(LocalAiTrainingQueryRepository repo, ObjectMapper json) {
        this.repo = repo;
        this.json = json;
    }

    /** Returns current market readiness for ranking model training. */
    public Map<String, Object> getReadiness() {
        Map<String, Object> report = new LinkedHashMap<>();
        try {
            Integer totalStocks = repo.countActiveSecurities();
            Integer stocksWithPrices = repo.countStocksWithCanonicalPrices();
            
            // Count securities with at least one canonical daily price since 2021.
            Integer eligibleStocks = repo.countEligibleStocks();
            if (eligibleStocks == null) eligibleStocks = 0;

            Integer badOhlc = repo.countBadOhlc();
            if (badOhlc == null) badOhlc = 0;

            String maxPriceDate = repo.findMaxPriceDate();
            String maxStatementDate = repo.findMaxStatementDate();

            // Index price coverage
            Map<String, Object> indexes = new LinkedHashMap<>();
            for (String code : List.of("VNINDEX", "VN30")) {
                Integer idxDays = repo.countIndexDays(code);
                boolean idxReady = idxDays != null && idxDays > 1000;
                indexes.put(code, Map.of("ready", idxReady, "valid_days", idxDays != null ? idxDays : 0));
            }

            boolean ready = (eligibleStocks >= 100) && (badOhlc == 0) &&
                Boolean.TRUE.equals(((Map<?, ?>) indexes.get("VNINDEX")).get("ready")) &&
                Boolean.TRUE.equals(((Map<?, ?>) indexes.get("VN30")).get("ready"));

            report.put("ready_to_train", ready);
            report.put("eligible_stocks", eligibleStocks);
            report.put("stock_target", 100);
            report.put("stocks_with_prices", stocksWithPrices != null ? stocksWithPrices : 0);
            report.put("total_active_securities", totalStocks != null ? totalStocks : 0);
            report.put("stock_ohlc_failures", badOhlc);
            report.put("latest_market_date", maxPriceDate);
            report.put("latest_statement_date", maxStatementDate);
            report.put("indexes", indexes);
            report.put("protocol_version", PROTOCOL_VERSION);
            report.put("disclaimer", RESEARCH_DISCLAIMER);

            String digest = sha256(json.writeValueAsString(report));
            report.put("readiness_sha256", digest);
        } catch (Exception e) {
            LOG.warn("Failed to compute database readiness: {}", e.getMessage());
            report.put("ready_to_train", false);
            report.put("error", e.getMessage());
        }
        return report;
    }

    /** Records readiness blocks while the verified trainer is unavailable. */
    public Map<String, Object> retrain(UUID idempotencyKey, String trigger, String requestedBy, String reason, int workers) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Reason is required");
        if (trigger == null || (!trigger.equals("MANUAL") && !trigger.equals("AUTO"))) trigger = "MANUAL";

        // 1. Check idempotency
        if (idempotencyKey != null) {
            var existing = repo.findRunByIdempotencyKey(idempotencyKey);
            if (!existing.isEmpty()) {
                return formatRun(existing.getFirst());
            }
        }

        // 2. Prevent concurrent training runs
        Integer runningCount = repo.countActiveTrainingRuns();
        if (runningCount != null && runningCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Another training run is already in progress");
        }

        UUID runId = UUID.randomUUID();
        Map<String, Object> readiness = getReadiness();
        String readinessSha = (String) readiness.get("readiness_sha256");

        if (!Boolean.TRUE.equals(readiness.get("ready_to_train"))) {
            repo.insertBlockedRun(runId, trigger, reason, requestedBy, idempotencyKey, readinessSha, PROTOCOL_VERSION);
            return getTrainingRun(runId);
        }

        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Verified Local AI trainer is not connected");
    }

    /** List recent training runs. */
    public List<Map<String, Object>> listTrainingRuns(int limit) {
        if (limit < 1 || limit > 100) limit = 50;
        var rows = repo.listTrainingRuns(limit);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var row : rows) {
            result.add(formatRun(row));
        }
        return result;
    }

    /** Get detailed training run. */
    public Map<String, Object> getTrainingRun(UUID id) {
        var rows = repo.findTrainingRun(id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Training run not found");
        return formatRun(rows.getFirst());
    }

    private Map<String, Object> formatRun(Map<String, Object> row) {
        Map<String, Object> map = new LinkedHashMap<>(row);
        for (String field : List.of("summary", "gate")) {
            if (map.get(field) != null) {
                try {
                    map.put(field, json.readTree(map.get(field).toString()));
                } catch (Exception ignored) {}
            }
        }
        return map;
    }

    public Map<String, Object> publish(UUID runId, String variant, String reason) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Local AI publishing requires verified training artifacts");
    }

    public Map<String, Object> rollback(String reason) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Local AI package rollback is unavailable");
    }

    private String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
