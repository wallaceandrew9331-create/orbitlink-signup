package com.orbitlink.signup.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MarketTest {

    @ParameterizedTest(name = "{0} -> {3}")
    @CsvSource({
            "Memphis,      35.1495,  -90.0490, US",
            "Austin,       30.2672,  -97.7431, US",
            "San Diego,    32.7157, -117.1611, US",
            "Tijuana,      32.5149, -117.0382, MX",
            "Monterrey,    25.6866, -100.3161, MX",
            "Guadalajara,  20.6597, -103.3496, MX",
            "Cancun,       21.1619,  -86.8515, MX",
            "Berlin,       52.5200,   13.4050, DE",
            "Tokyo,        35.6762,  139.6503, INTL",
    })
    void classifiesCities(String city, double lat, double lng, Market expected) {
        assertThat(Market.at(lat, lng)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"US, USD, 9900", "MX, MXN, 179900", "DE, EUR, 9900"})
    void depositsAreInMinorUnitsOfTheLocalCurrency(Market market, String currency, long minor) {
        assertThat(market.currency()).isEqualTo(currency);
        assertThat(market.depositMinor()).isEqualTo(minor);
    }
}
