package com.orbitlink.signup.orders;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OrderRequest(
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 100) @Pattern(regexp = "[^<>\\p{Cntrl}]+", message = "contains unsupported characters") String fullName,
        @NotNull @Pattern(regexp = "en-US|es-MX") String locale,
        @NotNull @Pattern(regexp = "tok_[a-z0-9_]{1,32}") String paymentToken) {

    public String normalizedEmail() {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * What "the same request" means for Idempotency-Key reuse. A retry must send the same body;
     * the same key with a different body is a client bug and gets a 422 instead of a silent replay.
     */
    public String fingerprint() {
        String canonical = String.join("|", lat.toString(), lng.toString(), normalizedEmail(),
                fullName.strip(), locale, paymentToken);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
