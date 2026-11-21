package com.hethongdata.taichinh.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AdminConsoleDtos {
    private AdminConsoleDtos() {}

    public record Session(boolean authenticated, String principal, String email, List<String> authorities) {}

    public record RuntimeStatus(String database, Map<String, SchedulerStatus> schedulers, String timezone,
            WorkerStatus adminWorkers) {}

    public record SchedulerStatus(boolean enabled) {}

    public record WorkerStatus(boolean commandsEnabled, boolean scheduleEnabled) {}

    public record CommandRequest(UUID idempotencyKey, String action, String reason, JsonNode input) {}

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

    public record ScheduleUpdate(boolean enabled, Set<String> jobCodes, long version, String reason) {}

    public record Schedule(String id, boolean enabled, @JsonProperty("job_codes") Set<String> jobCodes,
            long version, String reason, @JsonProperty("updated_at") Instant updatedAt,
            @JsonProperty("heartbeat_at") Instant heartbeatAt,
            @JsonProperty("last_sweep_started_at") Instant lastSweepStartedAt,
            @JsonProperty("last_sweep_finished_at") Instant lastSweepFinishedAt,
            @JsonProperty("last_error") String lastError, boolean workerEnabled, boolean legacySchedulerEnabled,
            String timezone) {}

    public record IssueReview(UUID id, @JsonProperty("event_type") String eventType, String reason,
            String principal, @JsonProperty("created_at") Instant createdAt) {}

    public record ReviewReceipt(UUID id, UUID validationResultId, boolean validationStatusChanged) {}

    public record NewsCandidate(UUID articleId, String title, @JsonProperty("published_at") Instant publishedAt) {}
}
