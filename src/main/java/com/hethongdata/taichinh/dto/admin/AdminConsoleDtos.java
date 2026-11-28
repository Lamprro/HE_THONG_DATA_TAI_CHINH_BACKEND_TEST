package com.hethongdata.taichinh.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AdminConsoleDtos {
    private AdminConsoleDtos() {}

    public record Session(boolean authenticated, String principal, String email, List<String> authorities) {}

    public record RuntimeStatus(String database, Map<String, SchedulerStatus> schedulers, String timezone,
            WorkerStatus adminWorkers) {}

    public record SchedulerStatus(boolean enabled) {}

    public record WorkerStatus(boolean commandsEnabled) {}

    public record CommandRequest(UUID idempotencyKey, String action, String reason, JsonNode input) {}

    public record HistoryPage<T>(List<T> content, long totalElements) {}

    public record LlmRunSummary(UUID id, String taskCode, String status, String symbol, UUID newsArticleId,
            String articleTitle, String provider, String modelName, String promptVersion, Integer httpStatus,
            int attemptCount, String errorMessage, Instant createdAt, Instant finishedAt, boolean retryable) {}

    public record AiCommandSummary(UUID id, String action, String resourceKey, String status,
            String outcome, String errorMessage, Instant createdAt) {}

    public record ReasonRequest(String reason) {}

    public record RetryRequest(UUID idempotencyKey, String reason, boolean reviewed) {}

    public record Command(UUID id,
            @JsonProperty("idempotency_key") UUID idempotencyKey,
            String action, @JsonProperty("resource_key") String resourceKey,
            String reason, String principal, String status,
            @JsonProperty("request_json") JsonNode request,
            @JsonProperty("result_json") JsonNode result,
            @JsonProperty("progress_json") JsonNode progress,
            @JsonProperty("parent_command_id") UUID parentCommandId,
            @JsonProperty("error_message") String errorMessage,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("started_at") Instant startedAt,
            @JsonProperty("finished_at") Instant finishedAt,
            @JsonProperty("acknowledgement_note") String acknowledgementNote) {}

    public record Summary(List<JobCount> jobs, List<StatusCount> commands, List<StatusCount> runs,
            List<VersionCount> versions, List<ValidationCount> validation) {}

    public record JobCount(@JsonProperty("is_active") Boolean active, long count) {}

    public record StatusCount(String status, long count) {}

    public record VersionCount(@JsonProperty("data_domain") String dataDomain, String status, long count) {}

    public record ValidationCount(@JsonProperty("validation_target") String validationTarget,
            @JsonProperty("result_status") String resultStatus, long count) {}

    public record IssueReview(UUID id, @JsonProperty("event_type") String eventType, String reason,
            String principal, @JsonProperty("created_at") Instant createdAt) {}

    public record ReviewReceipt(UUID id, UUID validationResultId, boolean validationStatusChanged) {}

    public record NewsCandidate(UUID articleId, String title, @JsonProperty("published_at") Instant publishedAt) {}
}
