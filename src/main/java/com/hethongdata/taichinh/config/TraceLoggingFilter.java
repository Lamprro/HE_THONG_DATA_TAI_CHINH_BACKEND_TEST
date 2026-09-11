package com.hethongdata.taichinh.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Filter that attaches a unique Correlation ID (traceId) to the MDC for every incoming HTTP request.
 * Propagates the traceId to the HTTP response header and logs request start/completion with execution time.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceLoggingFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(TraceLoggingFilter.class);
    private static final String MDC_TRACE_ID_KEY = "traceId";
    private static final String HEADER_REQUEST_ID = "X-Request-ID";
    private static final String HEADER_CORRELATION_ID = "X-Correlation-ID";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        long startTime = System.currentTimeMillis();
        String traceId = resolveTraceId(request);

        MDC.put(MDC_TRACE_ID_KEY, traceId);
        response.setHeader(HEADER_REQUEST_ID, traceId);

        String method = request.getMethod();
        String uri = request.getRequestURI();
        String queryString = request.getQueryString();
        String clientIp = resolveClientIp(request);

        LOGGER.info(">>> Incoming HTTP [{}] {} from IP: {}{}",
                method,
                uri,
                clientIp,
                queryString != null ? " (Query: " + queryString + ")" : "");

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - startTime;
            int status = response.getStatus();

            LOGGER.info("<<< Completed HTTP [{}] {} - Status: {} - Duration: {}ms",
                    method,
                    uri,
                    status,
                    durationMs);

            MDC.remove(MDC_TRACE_ID_KEY);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String requestId = request.getHeader(HEADER_REQUEST_ID);
        if (requestId != null && !requestId.isBlank()) {
            return requestId.trim();
        }
        String correlationId = request.getHeader(HEADER_CORRELATION_ID);
        if (correlationId != null && !correlationId.isBlank()) {
            return correlationId.trim();
        }
        return UUID.randomUUID().toString();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
