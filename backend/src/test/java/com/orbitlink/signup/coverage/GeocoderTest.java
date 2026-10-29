package com.orbitlink.signup.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeocoderTest {

    private final Geocoder geocoder = new Geocoder();

    @Test
    void everyTermMustMatch() {
        assertThat(geocoder.search("memphis")).hasSize(2);
        assertThat(geocoder.search("Beale, MEMPHIS")).singleElement()
                .satisfies(p -> assertThat(p.label()).startsWith("120 Beale St"));
        assertThat(geocoder.search("memphis berlin")).isEmpty();
    }
}
