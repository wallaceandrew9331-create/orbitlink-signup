package com.orbitlink.signup.outbox;

import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Write side of the transactional outbox. {@code MANDATORY} makes it an error to enqueue outside
 * a transaction: the whole point is that the message commits or rolls back with the state change.
 */
@Component
public class Outbox {

    public enum Kind { WAITLIST_JOINED, WAITLIST_PROMOTED, ORDER_CONFIRMED, ORDER_CANCELLED }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Outbox(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Kind kind, String recipient, Map<String, ?> payload) {
        jdbc.sql("INSERT INTO outbox (kind, recipient, payload) VALUES (?, ?, ?::jsonb)")
                .params(kind.name(), recipient, json.writeValueAsString(payload))
                .update();
    }
}
