package com.orbitlink.signup.admin;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orbitlink.signup.coverage.CoverageGrid;
import com.orbitlink.signup.coverage.FunnelCounter;
import com.orbitlink.signup.orders.CapacityService;
import com.orbitlink.signup.web.ApiExceptions.NotFoundException;
import com.uber.h3core.util.LatLng;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    record CellCounts(String h3Index, int capacity, int reserved, long waiting, long promoted,
                      long confirmed, long awaitingPayment, long cancelled) {}

    public record CellStats(String cell, double lat, double lng, String currency, int capacity, int reserved,
                            long waiting, long promoted, long confirmed, long awaitingPayment, long cancelled,
                            long lookups, Double conversion) {}

    public record AddCapacityRequest(@Min(1) @Max(10_000) int slots) {}

    public record AddCapacityResponse(String cell, int slotsAdded, int promoted) {}

    private final JdbcClient jdbc;
    private final FunnelCounter funnel;
    private final CapacityService capacity;
    private final CoverageGrid grid;

    public AdminController(JdbcClient jdbc, FunnelCounter funnel, CapacityService capacity, CoverageGrid grid) {
        this.jdbc = jdbc;
        this.funnel = funnel;
        this.capacity = capacity;
        this.grid = grid;
    }

    /** Conversion = confirmed orders / availability lookups in the cell. */
    @GetMapping("/cells")
    public List<CellStats> cells() {
        Map<String, Long> lookups = funnel.lookups();
        return jdbc.sql("""
                        SELECT c.h3_index, c.capacity, c.reserved,
                               c.waitlist_next - c.waitlist_promoted AS waiting,
                               c.waitlist_promoted AS promoted,
                               count(o.id) FILTER (WHERE o.status = 'CONFIRMED') AS confirmed,
                               count(o.id) FILTER (WHERE o.status = 'RESERVED')  AS awaiting_payment,
                               count(o.id) FILTER (WHERE o.status = 'CANCELLED') AS cancelled
                        FROM cells c LEFT JOIN orders o ON o.h3_index = c.h3_index
                        GROUP BY c.h3_index
                        ORDER BY c.h3_index""")
                .query(CellCounts.class)
                .list()
                .stream()
                .map(c -> {
                    LatLng center = grid.center(c.h3Index());
                    long seen = lookups.getOrDefault(c.h3Index(), 0L);
                    return new CellStats(c.h3Index(), center.lat, center.lng, grid.market(c.h3Index()).currency(),
                            c.capacity(), c.reserved(), c.waiting(), c.promoted(), c.confirmed(),
                            c.awaitingPayment(), c.cancelled(), seen, seen == 0 ? null : (double) c.confirmed() / seen);
                })
                .toList();
    }

    @PostMapping("/cells/{cell}/capacity")
    public AddCapacityResponse addCapacity(@PathVariable String cell, @Valid @RequestBody AddCapacityRequest request) {
        if (!grid.isServiceCell(cell)) {
            throw new NotFoundException("not a resolution-" + grid.resolution() + " H3 cell: " + cell);
        }
        int promoted = capacity.addCapacity(cell, request.slots());
        return new AddCapacityResponse(cell, request.slots(), promoted);
    }
}
