package com.orbitlink.signup;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base for tests against real Postgres and Redis. The containers are started once per JVM
 * (not per class) so Spring's cached application contexts never point at a stopped container.
 * Payments are instant and never time out unless a test scripts it; schedulers are pushed out
 * to an hour so tests drive the reconciler and outbox explicitly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "orbitlink.payments.timeout-rate=0",
        "orbitlink.payments.latency-min=0ms",
        "orbitlink.payments.latency-max=1ms",
        "orbitlink.payments.client-timeout=50ms",
        "orbitlink.reconciliation.interval=PT1H",
        "orbitlink.outbox.interval=PT1H",
        "spring.datasource.hikari.maximum-pool-size=20",
})
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        postgres.start();
        redis.start();
    }

    protected static final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @Autowired
    protected JsonMapper json;

    @BeforeEach
    void resetState() {
        jdbc.sql("TRUNCATE outbox, idempotency_keys, waitlist_entries, orders, cells").update();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    protected HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    protected HttpResponse<String> postOrder(String idempotencyKey, Map<String, ?> body, String forwardedFor) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri("/api/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        if (forwardedFor != null) {
            b.header("X-Forwarded-For", forwardedFor);
        }
        return send(b.build());
    }

    protected static Map<String, Object> orderBody(double lat, double lng, String email, String token) {
        return Map.of("lat", lat, "lng", lng, "email", email, "fullName", "Test Customer",
                "locale", "en-US", "paymentToken", token);
    }

    protected JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
