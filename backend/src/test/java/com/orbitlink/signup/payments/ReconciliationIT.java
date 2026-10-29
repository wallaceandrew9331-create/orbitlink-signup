package com.orbitlink.signup.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orbitlink.signup.IntegrationTest;
import com.orbitlink.signup.coverage.CellRepository;
import com.orbitlink.signup.coverage.CoverageGrid;
import com.orbitlink.signup.orders.Order;
import com.orbitlink.signup.orders.OrderRepository;
import com.orbitlink.signup.payments.FakePaymentProvider.Behaviour;

class ReconciliationIT extends IntegrationTest {

    private static final double LAT = 39.7475, LNG = -104.9930;

    @Autowired FakePaymentProvider provider;
    @Autowired ReconciliationJob reconciler;
    @Autowired OrderRepository orders;
    @Autowired CellRepository cells;
    @Autowired CoverageGrid grid;

    @Test
    void lostResponseIsConfirmedFromTheProviderRecord() {
        provider.script(Behaviour.LOST_RESPONSE);
        UUID id = place("lost-response@example.com", "tok_visa");
        assertThat(order(id).paymentStatus()).isEqualTo(Order.PaymentStatus.PENDING);

        reconciler.reconcile(Duration.ZERO);

        assertThat(order(id).status()).isEqualTo(Order.Status.CONFIRMED);
        assertThat(order(id).paymentAttempts()).as("no second charge attempt was needed").isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE kind = 'ORDER_CONFIRMED'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void lostRequestIsRetriedThenGivenUpAndTheSlotGoesToTheWaitlist() {
        String h3 = grid.cellFor(LAT, LNG);
        cells.ensureExists(h3, 1);
        provider.script(Behaviour.LOST_REQUEST, Behaviour.LOST_REQUEST, Behaviour.LOST_REQUEST);
        UUID id = place("unlucky@example.com", "tok_visa");
        place("next-in-line@example.com", "tok_visa");   // cell is full, so this one waits

        reconciler.reconcile(Duration.ZERO);   // attempt 2, lost again
        assertThat(order(id).paymentAttempts()).isEqualTo(2);
        reconciler.reconcile(Duration.ZERO);   // attempt 3, lost again
        assertThat(order(id).paymentAttempts()).isEqualTo(3);
        reconciler.reconcile(Duration.ZERO);   // out of attempts: cancel, release, promote

        assertThat(order(id).status()).isEqualTo(Order.Status.CANCELLED);
        assertThat(provider.lookup(PaymentService.providerKey(id))).as("the card was never charged").isEmpty();
        assertThat(jdbc.sql("SELECT email FROM orders WHERE status = 'RESERVED' AND source = 'WAITLIST'")
                .query(String.class).single()).isEqualTo("next-in-line@example.com");
        assertThat(cells.find(h3).orElseThrow().reserved()).isEqualTo(1);
    }

    @Test
    void declineCancelsAndFreesTheSlot() {
        UUID id = place("declined@example.com", FakePaymentProvider.DECLINE_TOKEN);
        assertThat(order(id).status()).isEqualTo(Order.Status.CANCELLED);
        assertThat(order(id).paymentStatus()).isEqualTo(Order.PaymentStatus.DECLINED);
        assertThat(cells.find(grid.cellFor(LAT, LNG)).orElseThrow().reserved()).isZero();
    }

    private UUID place(String email, String token) {
        var response = postOrder(newKey(), orderBody(LAT, LNG, email, token), null);
        assertThat(response.statusCode()).isEqualTo(201);
        String id = body(response).at("/order/id").asString("");
        return id.isEmpty() ? null : UUID.fromString(id);
    }

    private Order order(UUID id) {
        return orders.find(id).orElseThrow();
    }
}
