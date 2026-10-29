package com.orbitlink.signup.outbox;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.orbitlink.signup.OrbitlinkProperties;

/**
 * Delivers outbox rows. Delivery is at least once: if the send succeeds and the commit then
 * fails, the row is sent again next time, so the email provider should dedupe on the row id
 * (passed as the message id).
 */
@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final int MAX_ATTEMPTS = 10;

    public record Message(long id, String kind, String recipient, String payload, int attempts) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Notifier notifier;
    private final int batchSize;

    public OutboxWorker(JdbcClient jdbc, TransactionTemplate tx, Notifier notifier, OrbitlinkProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.notifier = notifier;
        this.batchSize = props.outbox().batchSize();
    }

    @Scheduled(fixedDelayString = "${orbitlink.outbox.interval}")
    public void poll() {
        int sent;
        do {
            sent = drainBatch();
        } while (sent == batchSize);
    }

    /**
     * SKIP LOCKED lets several workers (or replicas) drain the table without blocking on each
     * other or sending the same row twice concurrently. Rows stay ordered by id within a batch.
     */
    public int drainBatch() {
        Integer delivered = tx.execute(status -> {
            List<Message> batch = jdbc.sql("""
                            SELECT id, kind, recipient, payload::text AS payload, attempts FROM outbox
                            WHERE delivered_at IS NULL AND attempts < ?
                            ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED""")
                    .params(MAX_ATTEMPTS, batchSize)
                    .query(Message.class)
                    .list();
            int ok = 0;
            for (Message m : batch) {
                try {
                    notifier.send(m);
                    jdbc.sql("UPDATE outbox SET delivered_at = now(), attempts = attempts + 1 WHERE id = ?").param(m.id()).update();
                    ok++;
                } catch (RuntimeException e) {
                    log.warn("outbox {} delivery failed: {}", m.id(), e.getMessage());
                    jdbc.sql("UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE id = ?")
                            .params(e.getMessage(), m.id())
                            .update();
                }
            }
            return ok;
        });
        return delivered == null ? 0 : delivered;
    }
}
