package com.orbitlink.signup.orders;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record Order(
        UUID id,
        String h3Index,
        String email,
        String fullName,
        String locale,
        Status status,
        PaymentStatus paymentStatus,
        int paymentAttempts,
        @JsonIgnore String paymentToken,
        long depositMinor,
        String currency,
        Source source,
        OffsetDateTime createdAt) {

    /** RESERVED holds a slot; CONFIRMED means the deposit was captured; CANCELLED released the slot. */
    public enum Status { RESERVED, CONFIRMED, CANCELLED }

    /** PENDING means a charge was attempted and its outcome is unknown until the reconciler checks. */
    public enum PaymentStatus { NONE, PENDING, CAPTURED, DECLINED }

    public enum Source { DIRECT, WAITLIST }

    public boolean awaitingPayment() {
        return status == Status.RESERVED && paymentToken != null
                && (paymentStatus == PaymentStatus.NONE || paymentStatus == PaymentStatus.PENDING);
    }
}
