package com.orbitlink.signup.coverage;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CellRepository {

    public record Cell(String h3Index, int capacity, int reserved, long waitlistNext, long waitlistPromoted) {

        public int free() {
            return capacity - reserved;
        }

        public long waiting() {
            return waitlistNext - waitlistPromoted;
        }
    }

    private static final String COLUMNS = "h3_index, capacity, reserved, waitlist_next, waitlist_promoted";

    private final JdbcClient jdbc;

    public CellRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Cell> find(String h3Index) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM cells WHERE h3_index = ?")
                .param(h3Index)
                .query(Cell.class)
                .optional();
    }

    public List<Cell> findAll(Collection<String> h3Indexes) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM cells WHERE h3_index = ANY(?)")
                .param(h3Indexes.toArray(String[]::new))
                .query(Cell.class)
                .list();
    }

    public List<Cell> findAllTouched() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM cells ORDER BY h3_index").query(Cell.class).list();
    }

    /** Materialises a cell the first time someone orders in it. A no-op if it already exists. */
    public void ensureExists(String h3Index, int defaultCapacity) {
        jdbc.sql("INSERT INTO cells (h3_index, capacity) VALUES (?, ?) ON CONFLICT (h3_index) DO NOTHING")
                .params(h3Index, defaultCapacity)
                .update();
    }

    /**
     * Takes one slot if one is free. This is the oversell guard: the check and the increment are
     * one statement, so Postgres takes the row lock before evaluating {@code reserved < capacity}.
     * Under READ COMMITTED a transaction that waited on the lock re-evaluates the WHERE clause
     * against the row as the previous holder committed it, so the last slot goes to exactly one caller.
     */
    public boolean tryReserve(String h3Index) {
        return jdbc.sql("UPDATE cells SET reserved = reserved + 1 WHERE h3_index = ? AND reserved < capacity")
                .param(h3Index)
                .update() == 1;
    }

    /**
     * Hands out the next waitlist position, but only while the cell is still full. If a slot was
     * freed between a failed {@link #tryReserve} and this call it returns empty and the caller
     * goes back to reserving, so nobody is queued behind an empty slot.
     */
    public Optional<Long> nextWaitlistPositionIfFull(String h3Index) {
        return jdbc.sql("""
                        UPDATE cells SET waitlist_next = waitlist_next + 1
                        WHERE h3_index = ? AND reserved >= capacity
                        RETURNING waitlist_next""")
                .param(h3Index)
                .query(Long.class)
                .optional();
    }

    /** Explicit row lock for multi-step changes (capacity changes and promotion). */
    public Optional<Cell> lock(String h3Index) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM cells WHERE h3_index = ? FOR UPDATE")
                .param(h3Index)
                .query(Cell.class)
                .optional();
    }

    public void addCapacity(String h3Index, int slots) {
        jdbc.sql("UPDATE cells SET capacity = capacity + ? WHERE h3_index = ?").params(slots, h3Index).update();
    }

    public void release(String h3Index) {
        jdbc.sql("UPDATE cells SET reserved = reserved - 1 WHERE h3_index = ?").param(h3Index).update();
    }

    public void recordPromotions(String h3Index, int count) {
        jdbc.sql("UPDATE cells SET reserved = reserved + ?, waitlist_promoted = waitlist_promoted + ? WHERE h3_index = ?")
                .params(count, count, h3Index)
                .update();
    }
}
