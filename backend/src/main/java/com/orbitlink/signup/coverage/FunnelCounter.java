package com.orbitlink.signup.coverage;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Counts availability lookups per cell for the admin conversion rate. Kept in Redis rather than
 * Postgres so the read path never writes to the database. Analytics are best effort: a Redis
 * outage loses counts, never a customer request.
 */
@Component
public class FunnelCounter {

    private static final Logger log = LoggerFactory.getLogger(FunnelCounter.class);
    private static final String KEY = "funnel:lookups";

    private final StringRedisTemplate redis;

    public FunnelCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void recordLookup(String h3) {
        try {
            redis.opsForHash().increment(KEY, h3, 1);
        } catch (DataAccessException e) {
            log.warn("lookup counter unavailable: {}", e.getMessage());
        }
    }

    public Map<String, Long> lookups() {
        Map<String, Long> out = new HashMap<>();
        try {
            redis.<String, String>opsForHash().entries(KEY).forEach((k, v) -> out.put(k, Long.parseLong(v)));
        } catch (DataAccessException e) {
            log.warn("lookup counter unavailable: {}", e.getMessage());
        }
        return out;
    }
}
