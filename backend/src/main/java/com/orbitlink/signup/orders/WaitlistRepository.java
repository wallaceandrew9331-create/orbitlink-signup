package com.orbitlink.signup.orders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WaitlistRepository {

    private static final String COLUMNS = "id, h3_index, position, email, full_name, locale, status, order_id, created_at";

    private final JdbcClient jdbc;

    public WaitlistRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public WaitlistEntry insert(String h3Index, long position, String email, String fullName, String locale) {
        return jdbc.sql("""
                        INSERT INTO waitlist_entries (h3_index, position, email, full_name, locale, status)
                        VALUES (?, ?, ?, ?, ?, 'WAITING')
                        RETURNING""" + " " + COLUMNS)
                .params(h3Index, position, email, fullName, locale)
                .query(WaitlistEntry.class)
                .single();
    }

    public Optional<WaitlistEntry> find(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM waitlist_entries WHERE id = ?")
                .param(id)
                .query(WaitlistEntry.class)
                .optional();
    }

    public Optional<WaitlistEntry> findWaiting(String h3Index, String email) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM waitlist_entries WHERE h3_index = ? AND email = ? AND status = 'WAITING'")
                .params(h3Index, email)
                .query(WaitlistEntry.class)
                .optional();
    }

    /**
     * The head of the queue. No FOR UPDATE here: every waitlist mutation for a cell happens while
     * holding that cell's row lock, so the cell lock already serialises promotion.
     */
    public List<WaitlistEntry> head(String h3Index, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM waitlist_entries WHERE h3_index = ? AND status = 'WAITING' ORDER BY position LIMIT ?")
                .params(h3Index, limit)
                .query(WaitlistEntry.class)
                .list();
    }

    public void markPromoted(long id, UUID orderId) {
        jdbc.sql("UPDATE waitlist_entries SET status = 'PROMOTED', order_id = ?, promoted_at = now() WHERE id = ?")
                .params(orderId, id)
                .update();
    }
}
