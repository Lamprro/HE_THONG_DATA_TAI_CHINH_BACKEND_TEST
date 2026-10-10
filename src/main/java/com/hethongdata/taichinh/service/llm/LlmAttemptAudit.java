package com.hethongdata.taichinh.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One run owns all provider-call logs. Append atomically; never replace previous attempts. */
public final class LlmAttemptAudit {
    private LlmAttemptAudit() {}

    public static void append(JdbcTemplate db, LlmJson json, UUID run, JsonNode request,
                              LlmGateway.Attempt attempt) {
        if (request == null || attempt.number() < 1 || attempt.latencyMs() < 0
                || attempt.model() == null) throw new IllegalArgumentException("Invalid attempt audit");
        var entry = json.mapper().createObjectNode();
        entry.put("id", UUID.randomUUID().toString()).put("llm_run_id", run.toString())
            .put("attempt_no", attempt.number()).put("model_name", attempt.model())
            .put("http_status", attempt.httpStatus()).put("error_category", attempt.error())
            .put("response_body", attempt.rawBody()).put("response_text", attempt.outputText())
            .put("input_tokens", attempt.inputTokens()).put("output_tokens", attempt.outputTokens())
            .put("latency_ms", attempt.latencyMs()).put("created_at", Instant.now().toString());
        entry.set("request_payload", request);
        // PostgreSQL row locking serializes appenders and rechecks the predicate after a wait.
        int updated = db.update("""
            UPDATE llm_runs SET attempts=attempts || jsonb_build_array(?::jsonb)
            WHERE id=? AND NOT EXISTS (
                SELECT 1 FROM jsonb_array_elements(attempts) a WHERE (a->>'attempt_no')::integer=?)
            """, entry.toString(), run, attempt.number());
        if (updated != 1) throw new IllegalStateException("Run missing or duplicate attempt number: " + run);
    }

    public static List<JsonNode> read(JdbcTemplate db, LlmJson json, UUID run) {
        return db.query("""
            SELECT a::text FROM llm_runs r CROSS JOIN LATERAL jsonb_array_elements(r.attempts) a
            WHERE r.id=? ORDER BY (a->>'attempt_no')::integer
            """, (rs,n) -> json.read(rs.getString(1)), run);
    }
}
