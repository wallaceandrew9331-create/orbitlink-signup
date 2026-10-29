package com.orbitlink.signup.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.orbitlink.signup.OrbitlinkProperties;
import com.orbitlink.signup.payments.FakePaymentProvider.Behaviour;
import com.orbitlink.signup.payments.PaymentProvider.Charge;
import com.orbitlink.signup.payments.PaymentProvider.Outcome;
import com.orbitlink.signup.payments.PaymentProvider.PaymentTimeoutException;

class FakePaymentProviderTest {

    private final FakePaymentProvider provider = new FakePaymentProvider(new OrbitlinkProperties(
            5, 20, "t", false,
            new OrbitlinkProperties.Payments(0.0, Duration.ofMillis(1), Duration.ZERO, Duration.ZERO),
            null, null, null));

    private static Charge charge(String key, String token) {
        return new Charge(key, 9_900, "USD", token);
    }

    @Test
    void sameKeyChargesOnce() {
        assertThat(provider.charge(charge("k1", "tok_visa"))).isEqualTo(Outcome.CAPTURED);
        assertThat(provider.charge(charge("k1", "tok_visa"))).isEqualTo(Outcome.CAPTURED);
        assertThat(provider.chargesRecorded()).isEqualTo(1);
    }

    @Test
    void lostResponseStillCharged() {
        provider.script(Behaviour.LOST_RESPONSE);
        assertThatThrownBy(() -> provider.charge(charge("k2", "tok_visa"))).isInstanceOf(PaymentTimeoutException.class);
        assertThat(provider.lookup("k2")).contains(Outcome.CAPTURED);
    }

    @Test
    void lostRequestNeverReachedTheProvider() {
        provider.script(Behaviour.LOST_REQUEST);
        assertThatThrownBy(() -> provider.charge(charge("k3", "tok_visa"))).isInstanceOf(PaymentTimeoutException.class);
        assertThat(provider.lookup("k3")).isEmpty();
    }

    @Test
    void declineTokenIsDeclinedAndRemembered() {
        assertThat(provider.charge(charge("k4", FakePaymentProvider.DECLINE_TOKEN))).isEqualTo(Outcome.DECLINED);
        assertThat(provider.charge(charge("k4", "tok_visa"))).isEqualTo(Outcome.DECLINED);
    }
}
