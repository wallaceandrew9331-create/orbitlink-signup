package com.orbitlink.signup.payments;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.orbitlink.signup.OrbitlinkProperties;
import com.orbitlink.signup.orders.IdempotencyRepository;
import com.orbitlink.signup.orders.Order;
import com.orbitlink.signup.orders.OrderRepository;
import com.orbitlink.signup.orders.OrderService;
import com.orbitlink.signup.payments.PaymentProvider.Outcome;

/**
 * Resolves payments whose outcome is unknown. For each order stuck in PENDING it asks the
 * provider what it recorded under the order's key, instead of guessing:
 * <ul>
 *   <li>provider has a result: apply it (this is the "charged, but the response was lost" case);</li>
 *   <li>provider never saw it: retry the charge, up to a limit, then cancel and free the slot.</li>
 * </ul>
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);
    private static final Duration IDEMPOTENCY_KEY_TTL = Duration.ofHours(24);

    private final OrderRepository orders;
    private final OrderService orderService;
    private final PaymentService payments;
    private final PaymentProvider provider;
    private final IdempotencyRepository idempotency;
    private final Duration pendingOlderThan;
    private final int maxAttempts;

    public ReconciliationJob(OrderRepository orders, OrderService orderService, PaymentService payments,
                             PaymentProvider provider, IdempotencyRepository idempotency, OrbitlinkProperties props) {
        this.orders = orders;
        this.orderService = orderService;
        this.payments = payments;
        this.provider = provider;
        this.idempotency = idempotency;
        this.pendingOlderThan = props.reconciliation().pendingOlderThan();
        this.maxAttempts = props.reconciliation().maxAttempts();
    }

    @Scheduled(fixedDelayString = "${orbitlink.reconciliation.interval}")
    public void run() {
        reconcile(pendingOlderThan);
    }

    /** Returns how many orders it looked at. Public so tests can run a pass with no age threshold. */
    public int reconcile(Duration olderThan) {
        var stale = orders.findStalePending(olderThan, 100);
        for (Order order : stale) {
            Optional<Outcome> recorded = provider.lookup(PaymentService.providerKey(order.id()));
            if (recorded.isPresent()) {
                log.info("reconciled order {} from provider record: {}", order.id(), recorded.get());
                payments.settle(order.id(), recorded.get());
            } else if (order.paymentAttempts() < maxAttempts) {
                log.info("provider never saw order {}; retrying charge", order.id());
                payments.capture(order.id());
            } else {
                log.warn("giving up on order {} after {} attempts; releasing slot", order.id(), order.paymentAttempts());
                orderService.cancel(order.id(), Order.PaymentStatus.NONE);
            }
        }
        return stale.size();
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void purgeIdempotencyKeys() {
        int deleted = idempotency.deleteOlderThan(IDEMPOTENCY_KEY_TTL);
        if (deleted > 0) {
            log.info("purged {} idempotency keys older than {}", deleted, IDEMPOTENCY_KEY_TTL);
        }
    }
}
