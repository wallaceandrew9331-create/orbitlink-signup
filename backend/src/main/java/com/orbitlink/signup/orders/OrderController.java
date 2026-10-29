package com.orbitlink.signup.orders;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orbitlink.signup.orders.OrderService.Placement;
import com.orbitlink.signup.orders.OrderService.Reserved;
import com.orbitlink.signup.orders.OrderService.Waitlisted;
import com.orbitlink.signup.payments.PaymentService;
import com.orbitlink.signup.ratelimit.TokenBucketLimiter;
import com.orbitlink.signup.web.ApiExceptions.NotFoundException;
import com.orbitlink.signup.web.ApiExceptions.RateLimitedException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    public record WaitlistView(long id, String cell, long position, long ahead) {}

    public record PlacementResponse(String kind, Order order, WaitlistView waitlist) {}

    public record PayRequest(@NotNull @Pattern(regexp = "tok_[a-z0-9_]{1,32}") String paymentToken) {}

    private final OrderService orders;
    private final PaymentService payments;
    private final TokenBucketLimiter limiter;

    public OrderController(OrderService orders, PaymentService payments, TokenBucketLimiter limiter) {
        this.orders = orders;
        this.payments = payments;
        this.limiter = limiter;
    }

    /**
     * 201 when this request created the order or waitlist entry, 200 with
     * {@code Idempotent-Replayed: true} when an earlier request with the same key did.
     */
    @PostMapping
    public ResponseEntity<PlacementResponse> place(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{16,64}") String idempotencyKey,
            @Valid @RequestBody OrderRequest request) {
        var decision = limiter.tryAcquireForAccount(request.normalizedEmail());
        if (!decision.allowed()) {
            throw new RateLimitedException(decision.retryAfterMillis(), "too many order attempts for this account");
        }

        Placement placement = orders.place(idempotencyKey, request);
        PlacementResponse body = switch (placement) {
            case Reserved r -> {
                // Retries land here too: if the first attempt's charge timed out, this one asks again
                // with the same provider key, which can't double-charge.
                Order order = r.order().awaitingPayment() ? payments.capture(r.order().id()) : r.order();
                yield new PlacementResponse("ORDER", order, null);
            }
            case Waitlisted w -> new PlacementResponse("WAITLIST", null,
                    new WaitlistView(w.entry().id(), w.entry().h3Index(), w.entry().position(), w.ahead()));
        };
        return ResponseEntity.status(placement.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(placement.replayed()))
                .body(body);
    }

    @GetMapping("/{id}")
    public Order get(@PathVariable UUID id) {
        return orders.find(id).orElseThrow(() -> new NotFoundException("order not found"));
    }

    /** Deposit for an order promoted off the waitlist. Idempotent per order, like every capture. */
    @PostMapping("/{id}/pay")
    public Order pay(@PathVariable UUID id, @Valid @RequestBody PayRequest request) {
        orders.find(id).orElseThrow(() -> new NotFoundException("order not found"));
        return payments.captureWithToken(id, request.paymentToken());
    }
}
