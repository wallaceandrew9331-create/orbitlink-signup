package com.orbitlink.signup.orders;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WaitlistEntry(
        long id,
        String h3Index,
        long position,
        String email,
        String fullName,
        String locale,
        Status status,
        UUID orderId,
        OffsetDateTime createdAt) {

    public enum Status { WAITING, PROMOTED }
}
