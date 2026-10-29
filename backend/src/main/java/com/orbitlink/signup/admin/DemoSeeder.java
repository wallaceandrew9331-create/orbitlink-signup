package com.orbitlink.signup.admin;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.orbitlink.signup.coverage.CellRepository;
import com.orbitlink.signup.coverage.CoverageGrid;
import com.orbitlink.signup.orders.OrderRequest;
import com.orbitlink.signup.orders.OrderService;

/**
 * Gives the docker compose demo some full and nearly-full cells to look at. Goes through
 * {@link OrderService} like real traffic, so the seeded state obeys the same invariants.
 */
@Component
@ConditionalOnProperty(name = "orbitlink.demo-seed", havingValue = "true")
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private record Seed(String name, double lat, double lng, int capacity, int customers) {}

    private static final Seed[] SEEDS = {
            new Seed("memphis-downtown", 35.1395, -90.0526, 3, 7),   // full, 4 waiting
            new Seed("memphis-east", 35.1495, -89.9310, 10, 8),      // 2 left
            new Seed("austin", 30.2700, -97.7431, 20, 17),           // 3 left
            new Seed("monterrey", 25.6714, -100.3093, 5, 6),         // full, 1 waiting
            new Seed("berlin", 52.5170, 13.3889, 2, 4),              // full, 2 waiting
    };

    private final CellRepository cells;
    private final CoverageGrid grid;
    private final OrderService orders;

    public DemoSeeder(CellRepository cells, CoverageGrid grid, OrderService orders) {
        this.cells = cells;
        this.grid = grid;
        this.orders = orders;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!cells.findAllTouched().isEmpty()) {
            return;
        }
        for (Seed s : SEEDS) {
            cells.ensureExists(grid.cellFor(s.lat(), s.lng()), s.capacity());
            for (int i = 0; i < s.customers(); i++) {
                var req = new OrderRequest(s.lat(), s.lng(), "seed-%s-%d@example.com".formatted(s.name(), i),
                        "Seed Customer " + i, i % 2 == 0 ? "en-US" : "es-MX", "tok_visa");
                if (orders.place(UUID.randomUUID().toString(), req) instanceof OrderService.Reserved r) {
                    orders.confirmPayment(r.order().id());
                }
            }
        }
        log.info("demo seed: {} cells", SEEDS.length);
    }
}
