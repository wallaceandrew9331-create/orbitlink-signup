package com.orbitlink.signup.orders;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orbitlink.signup.OrbitlinkProperties;
import com.orbitlink.signup.coverage.CellRepository;
import com.orbitlink.signup.coverage.CoverageGrid;
import com.orbitlink.signup.coverage.Market;
import com.orbitlink.signup.outbox.Outbox;

@Service
public class OrderService {

    /** What a submission turned into. {@code replayed} is true when an earlier request with the same key did the work. */
    public sealed interface Placement {
        boolean replayed();
    }

    public record Reserved(Order order, boolean replayed) implements Placement {}

    public record Waitlisted(WaitlistEntry entry, long ahead, boolean replayed) implements Placement {}

    // Each loop iteration either succeeds or observed a capacity change that happened between
    // its two statements, so more than a couple of iterations means something is badly wrong.
    private static final int MAX_PLACEMENT_ATTEMPTS = 5;

    private final CoverageGrid grid;
    private final CellRepository cells;
    private final OrderRepository orders;
    private final WaitlistRepository waitlist;
    private final IdempotencyRepository idempotency;
    private final CapacityService capacity;
    private final Outbox outbox;
    private final int defaultCapacity;

    public OrderService(CoverageGrid grid, CellRepository cells, OrderRepository orders, WaitlistRepository waitlist,
                        IdempotencyRepository idempotency, CapacityService capacity, Outbox outbox,
                        OrbitlinkProperties props) {
        this.grid = grid;
        this.cells = cells;
        this.orders = orders;
        this.waitlist = waitlist;
        this.idempotency = idempotency;
        this.capacity = capacity;
        this.outbox = outbox;
        this.defaultCapacity = props.defaultCellCapacity();
    }

    /**
     * Reserves a slot in the customer's cell, or queues them if it is full. The idempotency key,
     * the slot and the order (or waitlist entry) commit together or not at all. The payment call
     * happens afterwards, outside this transaction, so a slow provider never holds a row lock.
     */
    @Transactional
    public Placement place(String idempotencyKey, OrderRequest req) {
        String fingerprint = req.fingerprint();
        if (!idempotency.claim(idempotencyKey, fingerprint)) {
            return replay(idempotencyKey, fingerprint);
        }

        String email = req.normalizedEmail();
        String h3 = grid.cellFor(req.lat(), req.lng());
        cells.ensureExists(h3, defaultCapacity);

        if (orders.findLive(h3, email).isPresent()) {
            throw new AlreadyOrderedException();
        }
        Optional<WaitlistEntry> alreadyWaiting = waitlist.findWaiting(h3, email);
        if (alreadyWaiting.isPresent()) {
            idempotency.attachWaitlistEntry(idempotencyKey, alreadyWaiting.get().id());
            return waitlisted(alreadyWaiting.get(), false);
        }

        for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
            if (cells.tryReserve(h3)) {
                Market market = grid.market(h3);
                Order order = new Order(UUID.randomUUID(), h3, email, req.fullName().strip(), req.locale(),
                        Order.Status.RESERVED, Order.PaymentStatus.NONE, 0, req.paymentToken(),
                        market.depositMinor(), market.currency(), Order.Source.DIRECT, null);
                orders.insert(order);
                idempotency.attachOrder(idempotencyKey, order.id());
                return new Reserved(orders.find(order.id()).orElseThrow(), false);
            }
            Optional<Long> position = cells.nextWaitlistPositionIfFull(h3);
            if (position.isPresent()) {
                WaitlistEntry entry = waitlist.insert(h3, position.get(), email, req.fullName().strip(), req.locale());
                idempotency.attachWaitlistEntry(idempotencyKey, entry.id());
                outbox.enqueue(Outbox.Kind.WAITLIST_JOINED, email,
                        Map.of("cell", h3, "position", entry.position(), "locale", entry.locale()));
                return waitlisted(entry, false);
            }
            // A slot was freed between the two statements; go round and try to take it.
        }
        throw new CapacityContentionException();
    }

    private Placement replay(String key, String fingerprint) {
        IdempotencyRepository.Claimed claimed = idempotency.find(key).orElseThrow();
        if (!claimed.requestHash().equals(fingerprint)) {
            throw new IdempotencyKeyReusedException();
        }
        if (claimed.orderId() != null) {
            return new Reserved(orders.find(claimed.orderId()).orElseThrow(), true);
        }
        return waitlisted(waitlist.find(claimed.waitlistEntryId()).orElseThrow(), true);
    }

    private Waitlisted waitlisted(WaitlistEntry entry, boolean replayed) {
        long promoted = cells.find(entry.h3Index()).map(CellRepository.Cell::waitlistPromoted).orElse(0L);
        return new Waitlisted(entry, Math.max(entry.position() - promoted - 1, 0), replayed);
    }

    @Transactional
    public void confirmPayment(UUID orderId) {
        if (orders.confirm(orderId)) {
            Order o = orders.find(orderId).orElseThrow();
            outbox.enqueue(Outbox.Kind.ORDER_CONFIRMED, o.email(), Map.of(
                    "orderId", o.id(), "locale", o.locale(), "depositMinor", o.depositMinor(), "currency", o.currency()));
        }
    }

    /** Cancels a reserved order and hands its slot to the next customer in line. */
    @Transactional
    public void cancel(UUID orderId, Order.PaymentStatus finalPaymentStatus) {
        orders.cancel(orderId, finalPaymentStatus).ifPresent(o -> {
            capacity.releaseSlot(o.h3Index());
            outbox.enqueue(Outbox.Kind.ORDER_CANCELLED, o.email(), Map.of("orderId", o.id(), "locale", o.locale()));
        });
    }

    public Optional<Order> find(UUID id) {
        return orders.find(id);
    }

    public static class AlreadyOrderedException extends RuntimeException {
        AlreadyOrderedException() {
            super("this email already has an order in this cell");
        }
    }

    public static class IdempotencyKeyReusedException extends RuntimeException {
        IdempotencyKeyReusedException() {
            super("Idempotency-Key was already used with a different request body");
        }
    }

    public static class CapacityContentionException extends RuntimeException {
        CapacityContentionException() {
            super("capacity in this cell is changing too quickly; retry");
        }
    }
}
