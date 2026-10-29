package com.orbitlink.signup.payments;

import java.util.Optional;

/**
 * The slice of a card processor's API this service uses. Like real processors, {@code charge}
 * is idempotent on {@code idempotencyKey}: sending the same key twice charges at most once and
 * returns the first outcome.
 */
public interface PaymentProvider {

    enum Outcome { CAPTURED, DECLINED }

    record Charge(String idempotencyKey, long amountMinor, String currency, String token) {}

    /** @throws PaymentTimeoutException when no answer came back in time; the charge may or may not have happened. */
    Outcome charge(Charge charge);

    /** What the provider recorded for a key, or empty if it never saw it. */
    Optional<Outcome> lookup(String idempotencyKey);

    class PaymentTimeoutException extends RuntimeException {
        public PaymentTimeoutException(String message) {
            super(message);
        }
    }
}
