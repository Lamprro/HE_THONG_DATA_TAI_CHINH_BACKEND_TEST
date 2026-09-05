package com.hethongdata.taichinh.dto.validation;

import java.util.UUID;

public record ValidationExecutionResponse(
        UUID rawPayloadId, UUID ingestionRunId, String status, int passed, int failed, int skipped,
        UUID dataVersionId, UUID quarantinedRecordId, boolean alreadyProcessed) {
}
