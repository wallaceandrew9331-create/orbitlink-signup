package com.orbitlink.signup.coverage;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.orbitlink.signup.OrbitlinkProperties;
import com.orbitlink.signup.coverage.CellRepository.Cell;

/** Read side of the coverage model. Never writes to Postgres: lookups are the hottest path. */
@Service
public class AvailabilityService {

    public enum Status { AVAILABLE, WAITLIST }

    public record Availability(
            String cell, int resolution, Status status, int slotsLeft, long waitlistDepth,
            Market market, String currency, long depositMinor) {}

    public record CellView(String cell, Status status, int capacity, int reserved, long waiting) {}

    private final CoverageGrid grid;
    private final CellRepository cells;
    private final FunnelCounter funnel;
    private final int defaultCapacity;

    public AvailabilityService(CoverageGrid grid, CellRepository cells, FunnelCounter funnel, OrbitlinkProperties props) {
        this.grid = grid;
        this.cells = cells;
        this.funnel = funnel;
        this.defaultCapacity = props.defaultCellCapacity();
    }

    public Availability check(double lat, double lng) {
        String h3 = grid.cellFor(lat, lng);
        Cell cell = cells.find(h3).orElseGet(() -> untouched(h3));
        funnel.recordLookup(h3);
        Market market = grid.market(h3);
        return new Availability(h3, grid.resolution(), status(cell), Math.max(cell.free(), 0), cell.waiting(),
                market, market.currency(), market.depositMinor());
    }

    /** Cells within k rings of the point, for drawing the map. */
    public List<CellView> around(double lat, double lng, int k) {
        List<String> disk = grid.disk(grid.cellFor(lat, lng), k);
        Map<String, Cell> stored = cells.findAll(disk).stream()
                .collect(Collectors.toMap(Cell::h3Index, Function.identity()));
        return disk.stream()
                .map(h3 -> stored.getOrDefault(h3, untouched(h3)))
                .map(c -> new CellView(c.h3Index(), status(c), c.capacity(), c.reserved(), c.waiting()))
                .toList();
    }

    private Cell untouched(String h3) {
        return new Cell(h3, defaultCapacity, 0, 0, 0);
    }

    private static Status status(Cell cell) {
        return cell.free() > 0 ? Status.AVAILABLE : Status.WAITLIST;
    }
}
