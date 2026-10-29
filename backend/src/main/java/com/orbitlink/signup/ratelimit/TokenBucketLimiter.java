package com.orbitlink.signup.ratelimit;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.orbitlink.signup.OrbitlinkProperties;
import com.orbitlink.signup.OrbitlinkProperties.Bucket;

@Component
public class TokenBucketLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketLimiter.class);

    public record Decision(boolean allowed, long retryAfterMillis) {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final RedisScript<List> SCRIPT =
            RedisScript.of(new ClassPathResource("token_bucket.lua"), List.class);

    private final StringRedisTemplate redis;
    private final OrbitlinkProperties.RateLimit config;

    public TokenBucketLimiter(StringRedisTemplate redis, OrbitlinkProperties props) {
        this.redis = redis;
        this.config = props.rateLimit();
    }

    public Decision tryAcquireForIp(String ip) {
        return tryAcquire("rl:ip:" + ip, config.perIp());
    }

    public Decision tryAcquireForAccount(String email) {
        return tryAcquire("rl:acct:" + email, config.perAccount());
    }

    /**
     * Fails open: if Redis is down we serve the request rather than turn a cache outage into a
     * full outage. The trade-off is no abuse protection while Redis is unavailable.
     */
    @SuppressWarnings("unchecked")
    Decision tryAcquire(String key, Bucket bucket) {
        if (!config.enabled()) {
            return new Decision(true, 0);
        }
        try {
            List<Long> result = redis.execute(SCRIPT, List.of(key),
                    String.valueOf(bucket.capacity()), String.valueOf(bucket.refillPerSecond()), "1");
            return new Decision(result.get(0) == 1L, result.get(1));
        } catch (DataAccessException e) {
            log.warn("rate limiter unavailable, allowing request: {}", e.getMessage());
            return new Decision(true, 0);
        }
    }
}
