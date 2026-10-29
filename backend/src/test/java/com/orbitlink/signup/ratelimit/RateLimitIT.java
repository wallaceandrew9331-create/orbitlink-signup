package com.orbitlink.signup.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.orbitlink.signup.IntegrationTest;

@TestPropertySource(properties = {
        "orbitlink.rate-limit.trust-forwarded-for=true",
        "orbitlink.rate-limit.per-ip.capacity=10",
        "orbitlink.rate-limit.per-ip.refill-per-second=0.01",
})
class RateLimitIT extends IntegrationTest {

    @Test
    void eachIpGetsItsOwnBucket() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(availability("203.0.113.7").statusCode()).isEqualTo(200);
        }
        HttpResponse<String> limited = availability("203.0.113.7");
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();

        assertThat(availability("198.51.100.20").statusCode()).as("a different client is unaffected").isEqualTo(200);
    }

    @Test
    void ordersAreAlsoLimitedPerAccountAcrossIps() {
        // Same account from five different addresses: the per-IP buckets never fill, the account one does.
        for (int i = 0; i < 5; i++) {
            int status = postOrder(newKey(), orderBody(35.1, -90.0, "busy@example.com", "tok_visa"), "192.0.2." + i).statusCode();
            assertThat(status).isIn(201, 409);
        }
        assertThat(postOrder(newKey(), orderBody(35.1, -90.0, "busy@example.com", "tok_visa"), "192.0.2.99").statusCode())
                .isEqualTo(429);
    }

    @Test
    void invalidInputIsRejectedBeforeTouchingTheDatabase() {
        assertThat(get("/api/availability?lat=123&lng=0").statusCode()).isEqualTo(400);
        assertThat(get("/api/cells?lat=0&lng=0&k=99").statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> availability(String ip) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/availability?lat=35.14&lng=-90.05"))
                .header("X-Forwarded-For", ip).build(), HttpResponse.BodyHandlers.ofString());
    }
}
