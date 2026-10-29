package com.orbitlink.signup.orders;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class OrderRequestTest {

    private static final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static OrderRequest request(String email, String name, String token) {
        return new OrderRequest(35.1395, -90.0526, email, name, "en-US", token);
    }

    @Test
    void fingerprintIgnoresEmailCaseAndSurroundingWhitespace() {
        assertThat(request(" Ada@Example.com", "Ada Lovelace ", "tok_visa").fingerprint())
                .isEqualTo(request("ada@example.com", "Ada Lovelace", "tok_visa").fingerprint());
    }

    @Test
    void fingerprintChangesWhenAnythingMeaningfulChanges() {
        String base = request("ada@example.com", "Ada", "tok_visa").fingerprint();
        assertThat(request("ada@example.com", "Ada", "tok_mastercard").fingerprint()).isNotEqualTo(base);
        assertThat(request("bob@example.com", "Ada", "tok_visa").fingerprint()).isNotEqualTo(base);
    }

    @Test
    void validationRejectsBadInput() {
        assertThat(validator.validate(request("ada@example.com", "Ada", "tok_visa"))).isEmpty();
        assertThat(validator.validate(request("not-an-email", "Ada", "tok_visa"))).hasSize(1);
        assertThat(validator.validate(request("ada@example.com", "<script>", "tok_visa"))).hasSize(1);
        assertThat(validator.validate(request("ada@example.com", "Ada", "4242424242424242"))).hasSize(1);
        assertThat(validator.validate(new OrderRequest(91.0, 0.0, "ada@example.com", "Ada", "fr-FR", "tok_visa")))
                .hasSize(2);
    }
}
