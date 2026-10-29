package com.orbitlink.signup.outbox;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Email stub: renders a localised subject line and logs it instead of calling a provider. */
@Component
public class Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    private static final Map<String, Map<String, String>> SUBJECTS = Map.of(
            "en-US", Map.of(
                    "WAITLIST_JOINED", "You're #%s on the Orbitlink waitlist",
                    "WAITLIST_PROMOTED", "Good news: a spot opened up in your area",
                    "ORDER_CONFIRMED", "Your Orbitlink order is confirmed",
                    "ORDER_CANCELLED", "Your Orbitlink order was cancelled"),
            "es-MX", Map.of(
                    "WAITLIST_JOINED", "Eres el n.º %s en la lista de espera de Orbitlink",
                    "WAITLIST_PROMOTED", "Buenas noticias: se liberó un lugar en tu zona",
                    "ORDER_CONFIRMED", "Tu pedido de Orbitlink está confirmado",
                    "ORDER_CANCELLED", "Tu pedido de Orbitlink fue cancelado"));

    private final JsonMapper json;

    public Notifier(JsonMapper json) {
        this.json = json;
    }

    public void send(OutboxWorker.Message m) {
        JsonNode payload = json.readTree(m.payload());
        String locale = payload.path("locale").asString("en-US");
        String template = SUBJECTS.getOrDefault(locale, SUBJECTS.get("en-US")).get(m.kind());
        String subject = template.formatted(payload.path("position").asString(""));
        log.info("EMAIL id={} to={} subject=\"{}\" payload={}", m.id(), m.recipient(), subject, m.payload());
    }
}
