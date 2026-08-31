package com.hethongdata.taichinh.application.port.error;

public enum ExternalErrorCategory {
    VALIDATION,
    UPSTREAM_CLIENT,
    RATE_LIMIT,
    UPSTREAM_SERVER,
    TIMEOUT,
    TRANSPORT,
    PROTOCOL
}
