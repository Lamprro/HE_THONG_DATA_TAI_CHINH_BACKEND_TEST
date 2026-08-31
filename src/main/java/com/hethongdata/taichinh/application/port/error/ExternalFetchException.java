package com.hethongdata.taichinh.application.port.error;

public class ExternalFetchException extends RuntimeException {

    private final ExternalErrorCategory category;
    private final Integer upstreamStatus;

    public ExternalFetchException(
            ExternalErrorCategory category,
            Integer upstreamStatus,
            String message,
            Throwable cause) {
        super(message, cause);
        this.category = category;
        this.upstreamStatus = upstreamStatus;
    }

    public ExternalFetchException(ExternalErrorCategory category, Integer upstreamStatus, String message) {
        this(category, upstreamStatus, message, null);
    }

    public ExternalErrorCategory category() {
        return category;
    }

    public Integer upstreamStatus() {
        return upstreamStatus;
    }
}
