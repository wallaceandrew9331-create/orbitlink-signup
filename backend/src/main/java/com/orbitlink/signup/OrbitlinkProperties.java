package com.orbitlink.signup;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orbitlink")
public record OrbitlinkProperties(
        int h3Resolution,
        int defaultCellCapacity,
        String adminToken,
        boolean demoSeed,
        Payments payments,
        Reconciliation reconciliation,
        Outbox outbox,
        RateLimit rateLimit) {

    public record Payments(double timeoutRate, Duration clientTimeout, Duration latencyMin, Duration latencyMax) {}

    public record Reconciliation(Duration interval, Duration pendingOlderThan, int maxAttempts) {}

    public record Outbox(Duration interval, int batchSize) {}

    public record RateLimit(boolean enabled, boolean trustForwardedFor, Bucket perIp, Bucket perAccount) {}

    public record Bucket(int capacity, double refillPerSecond) {}
}
