package com.orbitlink.signup.payments;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

import com.orbitlink.signup.OrbitlinkProperties;

/**
 * In-memory stand-in for a card processor that times out some of the time. The two timeout
 * modes are what make payments hard:
 * <ul>
 *   <li>{@link Behaviour#LOST_REQUEST}: the request never reached the provider. Nothing was charged.</li>
 *   <li>{@link Behaviour#LOST_RESPONSE}: the provider charged the card, but the answer was lost.</li>
 * </ul>
 * The caller sees the same exception in both cases, so it can't tell whether money moved. That is
 * why the order goes to PENDING and the reconciler asks the provider later instead of guessing.
 */
@Component
public class FakePaymentProvider implements PaymentProvider {

    public enum Behaviour { NORMAL, LOST_REQUEST, LOST_RESPONSE }

    public static final String DECLINE_TOKEN = "tok_decline";

    private final Map<String, Outcome> ledger = new ConcurrentHashMap<>();
    private final Queue<Behaviour> scripted = new ConcurrentLinkedQueue<>();
    private final double timeoutRate;
    private final Duration clientTimeout;
    private final Duration latencyMin;
    private final Duration latencyMax;

    public FakePaymentProvider(OrbitlinkProperties props) {
        OrbitlinkProperties.Payments p = props.payments();
        this.timeoutRate = p.timeoutRate();
        this.clientTimeout = p.clientTimeout();
        this.latencyMin = p.latencyMin();
        this.latencyMax = p.latencyMax();
    }

    @Override
    public Outcome charge(Charge charge) {
        sleep(randomBetween(latencyMin, latencyMax));
        Behaviour behaviour = nextBehaviour();
        if (behaviour == Behaviour.LOST_REQUEST) {
            timeOut();
        }
        Outcome outcome = ledger.computeIfAbsent(charge.idempotencyKey(),
                k -> DECLINE_TOKEN.equals(charge.token()) ? Outcome.DECLINED : Outcome.CAPTURED);
        if (behaviour == Behaviour.LOST_RESPONSE) {
            timeOut();
        }
        return outcome;
    }

    @Override
    public Optional<Outcome> lookup(String idempotencyKey) {
        return Optional.ofNullable(ledger.get(idempotencyKey));
    }

    /** Tests queue exact behaviours instead of relying on the random rate. */
    public void script(Behaviour... behaviours) {
        scripted.addAll(java.util.List.of(behaviours));
    }

    public int chargesRecorded() {
        return ledger.size();
    }

    private Behaviour nextBehaviour() {
        Behaviour next = scripted.poll();
        if (next != null) {
            return next;
        }
        double r = ThreadLocalRandom.current().nextDouble();
        if (r < timeoutRate / 2) {
            return Behaviour.LOST_REQUEST;
        }
        return r < timeoutRate ? Behaviour.LOST_RESPONSE : Behaviour.NORMAL;
    }

    /** Models what the caller experiences: it waits out its whole timeout, then gives up. */
    private void timeOut() {
        sleep(clientTimeout);
        throw new PaymentTimeoutException("payment provider did not answer within " + clientTimeout.toMillis() + " ms");
    }

    private static Duration randomBetween(Duration min, Duration max) {
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(min.toMillis(), max.toMillis() + 1));
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentTimeoutException("interrupted");
        }
    }
}
