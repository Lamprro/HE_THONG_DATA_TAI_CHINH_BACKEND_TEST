package com.hethongdata.taichinh.dto.validation;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.UUID;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public final class ValidationExecutionResponse {

    private final UUID rawPayloadId;

    private final UUID ingestionRunId;

    private final String status;

    private final int passed;

    private final int failed;

    private final int skipped;

    private final UUID dataVersionId;

    private final UUID quarantinedRecordId;

    private final boolean alreadyProcessed;
}
