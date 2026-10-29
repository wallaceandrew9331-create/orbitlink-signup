package com.orbitlink.signup.payments;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.orbitlink.signup.orders.Order;
import com.orbitlink.signup.orders.OrderRepository;
import com.orbitlink.signup.orders.OrderService;
import com.orbitlink.signup.payments.PaymentProvider.Outcome;
import com.orbitlink.signup.payments.PaymentProvider.PaymentTimeoutException;

/**
 * Takes the deposit for a reserved order. Safe to call any number of times for the same order
 * (double-clicks, client retries, the reconciler): the provider key is derived from the order
 * id, so it charges at most once, and the status transitions only fire from RESERVED.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentProvider provider;
    private final OrderRepository orders;
    private final OrderService orderService;

    public PaymentService(PaymentProvider provider, OrderRepository orders, OrderService orderService) {
        this.provider = provider;
        this.orders = orders;
        this.orderService = orderService;
    }

    public static String providerKey(UUID orderId) {
        return "order-" + orderId;
    }

    public Order capture(UUID orderId) {
        orders.beginPaymentAttempt(orderId).ifPresent(order -> {
            try {
                Outcome outcome = provider.charge(new PaymentProvider.Charge(
                        providerKey(order.id()), order.depositMinor(), order.currency(), order.paymentToken()));
                settle(order.id(), outcome);
            } catch (PaymentTimeoutException e) {
                // Unknown outcome: leave the order PENDING. The customer sees "confirming payment"
                // and the reconciler resolves it by asking the provider what happened.
                log.info("payment for order {} timed out (attempt {}); leaving PENDING", order.id(), order.paymentAttempts());
            }
        });
        return orders.find(orderId).orElseThrow();
    }

    /** For customers promoted off the waitlist, who choose a payment method after the fact. */
    public Order captureWithToken(UUID orderId, String token) {
        orders.setPaymentToken(orderId, token);
        return capture(orderId);
    }

    void settle(UUID orderId, Outcome outcome) {
        switch (outcome) {
            case CAPTURED -> orderService.confirmPayment(orderId);
            case DECLINED -> orderService.cancel(orderId, Order.PaymentStatus.DECLINED);
        }
    }
}
