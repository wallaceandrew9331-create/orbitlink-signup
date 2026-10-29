package com.orbitlink.signup.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;

import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import com.orbitlink.signup.IntegrationTest;
import com.orbitlink.signup.coverage.CellRepository;
import com.orbitlink.signup.coverage.CoverageGrid;
import com.orbitlink.signup.orders.OrderService.Placement;
import com.orbitlink.signup.orders.OrderService.Reserved;
import com.orbitlink.signup.orders.OrderService.Waitlisted;

/**
 * The core guarantee: hundreds of customers hit one cell with N slots at the same instant, and
 * exactly N get a slot. Everyone else gets a waitlist position, with no gaps or duplicates.
 * Repeated because a race that loses one time in five would still pass a single run.
 */
class OversellIT extends IntegrationTest {

    private static final double LAT = 35.1395, LNG = -90.0526;
    private static final int SLOTS = 25;
    private static final int CUSTOMERS = 500;

    @Autowired OrderService orders;
    @Autowired CellRepository cells;
    @Autowired CoverageGrid grid;

    @RepeatedTest(3)
    void lastSlotIsNeverOversold() throws Exception {
        String h3 = grid.cellFor(LAT, LNG);
        cells.ensureExists(h3, SLOTS);

        List<Placement> results = fireConcurrently(CUSTOMERS, i -> orders.place(
                "oversell-key-" + i + "-" + System.nanoTime(),
                new OrderRequest(LAT, LNG, "customer" + i + "@example.com", "Customer " + i, "en-US", "tok_visa")));

        long reserved = results.stream().filter(Reserved.class::isInstance).count();
        List<Long> positions = results.stream().filter(Waitlisted.class::isInstance)
                .map(p -> ((Waitlisted) p).entry().position()).sorted().toList();

        assertThat(reserved).as("orders that got a slot").isEqualTo(SLOTS);
        assertThat(positions).as("waitlist positions are exactly 1..%d", CUSTOMERS - SLOTS)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, CUSTOMERS - SLOTS).boxed().toList());

        // Check what was committed, not just what the callers were told.
        assertThat(jdbc.sql("SELECT count(*) FROM orders WHERE h3_index = ? AND status <> 'CANCELLED'")
                .param(h3).query(Long.class).single()).isEqualTo(SLOTS);
        CellRepository.Cell cell = cells.find(h3).orElseThrow();
        assertThat(cell.reserved()).isEqualTo(SLOTS);
        assertThat(cell.waiting()).isEqualTo(CUSTOMERS - SLOTS);
    }

    interface Task<T> {
        T run(int i) throws Exception;
    }

    /** Starts every task, then releases them through one gate so they really do overlap. */
    static <T> List<T> fireConcurrently(int n, Task<T> task) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int id = i;
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.run(id);
                }));
            }
            gate.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        }
    }
}
