package com.orbitlink.signup.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.orbitlink.signup.IntegrationTest;
import com.orbitlink.signup.payments.FakePaymentProvider;
import com.orbitlink.signup.payments.FakePaymentProvider.Behaviour;

// The 30-way duplicate burst below is one account, so the per-account bucket (5) would turn most
// of it into 429s. That behaviour is covered in RateLimitIT; here we only want idempotency.
@TestPropertySource(properties = "orbitlink.rate-limit.per-account.capacity=100")
class IdempotencyIT extends IntegrationTest {

    private static final double LAT = 47.6114, LNG = -122.3370;

    @Autowired FakePaymentProvider provider;

    @Test
    void concurrentDuplicatesCreateOneOrder() throws Exception {
        String key = newKey();
        Map<String, Object> body = orderBody(LAT, LNG, "double-click@example.com", "tok_visa");

        List<HttpResponse<String>> responses = OversellIT.fireConcurrently(30, i -> postOrder(key, body, null));

        assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(200, 201);
        assertThat(responses).filteredOn(r -> r.statusCode() == 201).hasSize(1);
        assertThat(responses).filteredOn(r -> r.headers().firstValue("Idempotent-Replayed").orElse("").equals("true"))
                .hasSize(29);
        assertThat(responses).extracting(r -> body(r).at("/order/id").asString()).containsOnly(
                body(responses.getFirst()).at("/order/id").asString());
        assertThat(jdbc.sql("SELECT count(*) FROM orders").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        String key = newKey();
        assertThat(postOrder(key, orderBody(LAT, LNG, "a@example.com", "tok_visa"), null).statusCode()).isEqualTo(201);
        HttpResponse<String> reused = postOrder(key, orderBody(LAT, LNG, "b@example.com", "tok_visa"), null);
        assertThat(reused.statusCode()).isEqualTo(422);
    }

    @Test
    void keyIsRequiredAndValidated() {
        assertThat(postOrder(null, orderBody(LAT, LNG, "a@example.com", "tok_visa"), null).statusCode()).isEqualTo(400);
        assertThat(postOrder("short", orderBody(LAT, LNG, "a@example.com", "tok_visa"), null).statusCode()).isEqualTo(400);
    }

    @Test
    void retryAfterPaymentTimeoutConfirmsWithoutChargingTwice() {
        String key = newKey();
        Map<String, Object> body = orderBody(LAT, LNG, "retry@example.com", "tok_visa");
        int chargesBefore = provider.chargesRecorded();

        // The provider charges the card but the response is lost: the client sees "pending".
        provider.script(Behaviour.LOST_RESPONSE);
        HttpResponse<String> first = postOrder(key, body, null);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(body(first).at("/order/paymentStatus").asString()).isEqualTo("PENDING");

        // The client retries with the same key; the server re-asks the provider with the same charge key.
        HttpResponse<String> retry = postOrder(key, body, null);
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(body(retry).at("/order/status").asString()).isEqualTo("CONFIRMED");
        assertThat(body(retry).at("/order/paymentAttempts").asInt()).isEqualTo(2);
        assertThat(provider.chargesRecorded() - chargesBefore).isEqualTo(1);
    }

    @Test
    void duplicateEmailWithFreshKeyIsAConflictNotASecondSlot() {
        assertThat(postOrder(newKey(), orderBody(LAT, LNG, "twice@example.com", "tok_visa"), null).statusCode()).isEqualTo(201);
        assertThat(postOrder(newKey(), orderBody(LAT, LNG, "TWICE@example.com", "tok_visa"), null).statusCode()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT reserved FROM cells").query(Integer.class).single()).isEqualTo(1);
    }
}
