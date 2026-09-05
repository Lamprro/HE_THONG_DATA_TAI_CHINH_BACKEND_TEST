package com.hethongdata.taichinh.dto;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public final class ApiErrorResponse {

    private final Instant timestamp;

    private final String category;

    private final UUID runId;

    private final Integer upstreamStatus;

    private final String message;
}
