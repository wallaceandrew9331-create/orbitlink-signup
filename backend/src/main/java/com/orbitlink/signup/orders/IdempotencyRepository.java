package com.orbitlink.signup.orders;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    public record Claimed(String requestHash, UUID orderId, Long waitlistEntryId) {}

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns true if this transaction now owns the key. If another transaction inserted the same
     * key and hasn't finished, Postgres makes this INSERT wait on the unique index: if that
     * transaction commits we get the conflict (false) and replay its result; if it rolls back we
     * get the key. So there is never an "in progress" state to poll.
     */
    public boolean claim(String key, String requestHash) {
        return jdbc.sql("INSERT INTO idempotency_keys (key, request_hash) VALUES (?, ?) ON CONFLICT (key) DO NOTHING")
                .params(key, requestHash)
                .update() == 1;
    }

    public Optional<Claimed> find(String key) {
        return jdbc.sql("SELECT request_hash, order_id, waitlist_entry_id FROM idempotency_keys WHERE key = ?")
                .param(key)
                .query(Claimed.class)
                .optional();
    }

    public void attachOrder(String key, UUID orderId) {
        jdbc.sql("UPDATE idempotency_keys SET order_id = ? WHERE key = ?").params(orderId, key).update();
    }

    public void attachWaitlistEntry(String key, long entryId) {
        jdbc.sql("UPDATE idempotency_keys SET waitlist_entry_id = ? WHERE key = ?").params(entryId, key).update();
    }

    public int deleteOlderThan(Duration age) {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE created_at < now() - make_interval(secs => ?)")
                .param((double) age.toSeconds())
                .update();
    }
}
