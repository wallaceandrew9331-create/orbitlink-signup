package com.orbitlink.signup.orders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orbitlink.signup.OrbitlinkProperties;
import com.orbitlink.signup.coverage.CellRepository;
import com.orbitlink.signup.coverage.CellRepository.Cell;
import com.orbitlink.signup.coverage.CoverageGrid;
import com.orbitlink.signup.coverage.Market;
import com.orbitlink.signup.outbox.Outbox;

/**
 * Everything that frees capacity in a cell goes through here, and it always promotes from the
 * waitlist in the same transaction. That keeps one invariant true at every commit:
 * <b>a cell with a free slot has an empty waitlist</b>. New orders can therefore take any free
 * slot they find without jumping the queue.
 */
@Service
public class CapacityService {

    private final CellRepository cells;
    private final WaitlistRepository waitlist;
    private final OrderRepository orders;
    private final Outbox outbox;
    private final CoverageGrid grid;
    private final int defaultCapacity;

    public CapacityService(CellRepository cells, WaitlistRepository waitlist, OrderRepository orders,
                           Outbox outbox, CoverageGrid grid, OrbitlinkProperties props) {
        this.cells = cells;
        this.waitlist = waitlist;
        this.orders = orders;
        this.outbox = outbox;
        this.grid = grid;
        this.defaultCapacity = props.defaultCellCapacity();
    }

    /** Admin action: a new satellite shell adds slots to a cell. Returns how many customers were promoted. */
    @Transactional
    public int addCapacity(String h3, int slots) {
        cells.ensureExists(h3, defaultCapacity);
        cells.lock(h3);
        cells.addCapacity(h3, slots);
        return promote(h3);
    }

    /** Gives back the slot of an order that was just cancelled. Must join the cancelling transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseSlot(String h3) {
        cells.lock(h3);
        cells.release(h3);
        promote(h3);
    }

    /**
     * Moves the head of the waitlist into free slots, oldest position first. The caller holds the
     * cell row lock, which every waitlist join and promotion for this cell also needs, so two
     * concurrent capacity changes can't promote the same entry or skip one.
     */
    private int promote(String h3) {
        Cell cell = cells.lock(h3).orElseThrow();
        int free = cell.free();
        if (free <= 0 || cell.waiting() == 0) {
            return 0;
        }
        Market market = grid.market(h3);
        List<WaitlistEntry> head = waitlist.head(h3, free);
        for (WaitlistEntry e : head) {
            Order order = new Order(UUID.randomUUID(), h3, e.email(), e.fullName(), e.locale(),
                    Order.Status.RESERVED, Order.PaymentStatus.NONE, 0, null,
                    market.depositMinor(), market.currency(), Order.Source.WAITLIST, null);
            orders.insert(order);
            waitlist.markPromoted(e.id(), order.id());
            outbox.enqueue(Outbox.Kind.WAITLIST_PROMOTED, e.email(), Map.of(
                    "orderId", order.id(), "cell", h3, "position", e.position(), "locale", e.locale(),
                    "depositMinor", market.depositMinor(), "currency", market.currency()));
        }
        cells.recordPromotions(h3, head.size());
        return head.size();
    }
}
