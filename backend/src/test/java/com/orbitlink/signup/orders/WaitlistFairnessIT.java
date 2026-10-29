package com.orbitlink.signup.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.orbitlink.signup.IntegrationTest;
import com.orbitlink.signup.coverage.CellRepository;
import com.orbitlink.signup.coverage.CoverageGrid;

/**
 * Capacity is added in several concurrent admin calls while new customers keep joining. The
 * customers promoted must be exactly the oldest positions, each promoted once, and the people
 * still waiting must be the contiguous rest of the queue.
 */
class WaitlistFairnessIT extends IntegrationTest {

    private static final double LAT = 30.2700, LNG = -97.7431;

    @Autowired OrderService orders;
    @Autowired CapacityService capacity;
    @Autowired CellRepository cells;
    @Autowired CoverageGrid grid;

    @Test
    void concurrentCapacityAdditionsPromoteStrictlyInOrder() throws Exception {
        String h3 = grid.cellFor(LAT, LNG);
        cells.ensureExists(h3, 0);

        // 200 customers queue up in a cell with no capacity at all.
        OversellIT.fireConcurrently(200, i -> orders.place("early-" + i + "-" + System.nanoTime(), request("early" + i)));

        // Then 10 admins each add 5 slots while 100 more customers join, all at once.
        int[] promoted = new int[1];
        OversellIT.fireConcurrently(110, i -> {
            if (i < 10) {
                int n = capacity.addCapacity(h3, 5);
                synchronized (promoted) {
                    promoted[0] += n;
                }
                return null;
            }
            return orders.place("late-" + i + "-" + System.nanoTime(), request("late" + i));
        });

        assertThat(promoted[0]).isEqualTo(50);

        List<Long> promotedPositions = jdbc.sql(
                        "SELECT position FROM waitlist_entries WHERE h3_index = ? AND status = 'PROMOTED' ORDER BY position")
                .param(h3).query(Long.class).list();
        List<Long> waitingPositions = jdbc.sql(
                        "SELECT position FROM waitlist_entries WHERE h3_index = ? AND status = 'WAITING' ORDER BY position")
                .param(h3).query(Long.class).list();

        assertThat(promotedPositions).as("the 50 oldest positions were promoted")
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 50).boxed().toList());
        assertThat(waitingPositions).as("everyone else is still queued, without gaps")
                .containsExactlyElementsOf(LongStream.rangeClosed(51, 300).boxed().toList());

        // Every promotion produced exactly one order and one notification.
        assertThat(jdbc.sql("SELECT count(DISTINCT order_id) FROM waitlist_entries WHERE h3_index = ? AND status = 'PROMOTED'")
                .param(h3).query(Long.class).single()).isEqualTo(50);
        assertThat(jdbc.sql("SELECT count(*) FROM orders WHERE h3_index = ? AND source = 'WAITLIST'")
                .param(h3).query(Long.class).single()).isEqualTo(50);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE kind = 'WAITLIST_PROMOTED'")
                .query(Long.class).single()).isEqualTo(50);

        CellRepository.Cell cell = cells.find(h3).orElseThrow();
        assertThat(cell.capacity()).isEqualTo(50);
        assertThat(cell.reserved()).isEqualTo(50);
        assertThat(cell.waiting()).isEqualTo(250);
    }

    @Test
    void cancellingAnOrderHandsTheSlotToTheHeadOfTheQueue() {
        String h3 = grid.cellFor(LAT, LNG);
        cells.ensureExists(h3, 1);
        var first = (OrderService.Reserved) orders.place(newKey(), request("first"));
        orders.place(newKey(), request("second"));
        orders.place(newKey(), request("third"));

        orders.cancel(first.order().id(), Order.PaymentStatus.DECLINED);

        assertThat(jdbc.sql("SELECT email FROM orders WHERE h3_index = ? AND status = 'RESERVED'")
                .param(h3).query(String.class).list()).containsExactly("second@example.com");
        assertThat(cells.find(h3).orElseThrow().waiting()).isEqualTo(1);
    }

    private static OrderRequest request(String name) {
        return new OrderRequest(LAT, LNG, name + "@example.com", name, "es-MX", "tok_visa");
    }
}
