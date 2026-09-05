package com.hethongdata.taichinh.service.ingestion;

import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Transient, per-job retry budget. Redis operations are atomic at the decrement step; the database
 * remains the durable source for an exhausted/disabled job.
 */
@Service
public class RetryBudgetService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RetryBudgetService.class);
    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final Duration ttl;

    public RetryBudgetService(
            StringRedisTemplate redis,
            @Value("${financial.ingestion.retry-budget.key-prefix}") String keyPrefix,
            @Value("${financial.ingestion.retry-budget.ttl}") Duration ttl) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
        this.ttl = ttl;
    }

    /**
     * Returns the remaining attempts, or null when Redis is unavailable and no destructive decision
     * is made.
     */
    public Long consumeFailedAttempt(IngestionJobEntity job) {
        try {
            String key = key(job);
            redis.opsForValue().setIfAbsent(key, Short.toString(job.getMaxRetries()), ttl);
            Long remaining = redis.opsForValue().decrement(key);
            redis.expire(key, ttl);
            return remaining;
        } catch (DataAccessException exception) {
            LOGGER.error(
                    "Redis retry budget is unavailable for ingestion job {}; preserving its active state",
                    job.getCode());
            return null;
        }
    }

    /**
     * A successful source call starts a fresh failure budget for subsequent independent incidents.
     */
    public void resetAfterSuccess(IngestionJobEntity job) {
        try {
            redis.delete(key(job));
        } catch (DataAccessException exception) {
            LOGGER.warn("Could not clear Redis retry budget for ingestion job {}", job.getCode());
        }
    }

    private String key(IngestionJobEntity job) {
        return keyPrefix + job.getId();
    }
}
