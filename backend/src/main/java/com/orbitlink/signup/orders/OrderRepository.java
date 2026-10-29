package com.orbitlink.signup.orders;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    private static final String COLUMNS = """
            id, h3_index, email, full_name, locale, status, payment_status, payment_attempts,
            payment_token, deposit_minor, currency, source, created_at""";

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Something this customer already holds in a cell: an order (entry id null) or a waitlist spot. */
    public record Existing(Long waitlistEntryId) {}

    public Order insert(Order o) {
        return jdbc.sql("""
                        INSERT INTO orders (id, h3_index, email, full_name, locale, status, payment_status,
                                            payment_token, deposit_minor, currency, source)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING""" + " " + COLUMNS)
                .params(o.id(), o.h3Index(), o.email(), o.fullName(), o.locale(), o.status().name(),
                        o.paymentStatus().name(), o.paymentToken(), o.depositMinor(), o.currency(), o.source().name())
                .query(Order.class)
                .single();
    }

    /**
     * Live order or waiting entry for this email in this cell, in one round trip. It reads two
     * tables, which is why it isn't split across the two repositories.
     */
    public Optional<Existing> findExisting(String h3Index, String email) {
        return jdbc.sql("""
                        SELECT NULL::bigint AS waitlist_entry_id FROM orders
                        WHERE h3_index = ? AND email = ? AND status <> 'CANCELLED'
                        UNION ALL
                        SELECT id FROM waitlist_entries
                        WHERE h3_index = ? AND email = ? AND status = 'WAITING'
                        LIMIT 1""")
                .params(h3Index, email, h3Index, email)
                .query(Existing.class)
                .optional();
    }

    public Optional<Order> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM orders WHERE id = ?").param(id).query(Order.class).optional();
    }

    public void setPaymentToken(UUID id, String token) {
        jdbc.sql("UPDATE orders SET payment_token = ?, updated_at = now() WHERE id = ? AND status = 'RESERVED' AND payment_status = 'NONE'")
                .params(token, id)
                .update();
    }

    /**
     * Records that a charge is about to be attempted. Only orders still waiting for money match,
     * so a late retry of an order that already settled does nothing.
     */
    public Optional<Order> beginPaymentAttempt(UUID id) {
        return jdbc.sql("""
                        UPDATE orders
                        SET payment_status = 'PENDING', payment_attempts = payment_attempts + 1, updated_at = now()
                        WHERE id = ? AND status = 'RESERVED' AND payment_status IN ('NONE', 'PENDING')
                          AND payment_token IS NOT NULL
                        RETURNING""" + " " + COLUMNS)
                .param(id)
                .query(Order.class)
                .optional();
    }

    /** RESERVED -> CONFIRMED. Returns false if another path (a retry, the reconciler) got there first. */
    public boolean confirm(UUID id) {
        return jdbc.sql("""
                        UPDATE orders SET status = 'CONFIRMED', payment_status = 'CAPTURED', updated_at = now()
                        WHERE id = ? AND status = 'RESERVED'""")
                .param(id)
                .update() == 1;
    }

    /** RESERVED -> CANCELLED. Returns the order if this call made the transition, so the slot is released once. */
    public Optional<Order> cancel(UUID id, Order.PaymentStatus paymentStatus) {
        return jdbc.sql("""
                        UPDATE orders SET status = 'CANCELLED', payment_status = ?, updated_at = now()
                        WHERE id = ? AND status = 'RESERVED'
                        RETURNING""" + " " + COLUMNS)
                .params(paymentStatus.name(), id)
                .query(Order.class)
                .optional();
    }

    public List<Order> findStalePending(Duration olderThan, int limit) {
        return jdbc.sql("""
                        SELECT %s FROM orders
                        WHERE payment_status = 'PENDING' AND updated_at < now() - make_interval(secs => ?)
                        ORDER BY updated_at LIMIT ?""".formatted(COLUMNS))
                .params(olderThan.toMillis() / 1000.0, limit)
                .query(Order.class)
                .list();
    }
}
