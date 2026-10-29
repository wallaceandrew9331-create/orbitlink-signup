package com.orbitlink.signup.coverage;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Fixture geocoder. A real deployment would call a geocoding provider; that is an external
 * dependency with its own quotas and terms, and it isn't what this project is about. The
 * addresses are made up; the coordinates are real city centres.
 */
@Component
public class Geocoder {

    public record Place(String label, double lat, double lng) {}

    private static final List<Place> PLACES = List.of(
            new Place("120 Beale St, Memphis, TN 38103, USA", 35.1395, -90.0526),
            new Place("4410 Summer Ave, Memphis, TN 38122, USA", 35.1495, -89.9310),
            new Place("8840 Stateline Rd, Southaven, MS 38671, USA", 34.9889, -90.0126),
            new Place("701 Congress Ave, Austin, TX 78701, USA", 30.2700, -97.7431),
            new Place("1600 Champa St, Denver, CO 80202, USA", 39.7475, -104.9930),
            new Place("400 Pine St, Seattle, WA 98101, USA", 47.6114, -122.3370),
            new Place("8 Ranch Rd, Bozeman, MT 59715, USA", 45.6770, -111.0429),
            new Place("21 Harbor Way, Boston, MA 02110, USA", 42.3554, -71.0490),
            new Place("Av. Constitución 300, Monterrey, NL, México", 25.6714, -100.3093),
            new Place("Av. Juárez 976, Guadalajara, Jal., México", 20.6736, -103.3440),
            new Place("Calle Macedonio Alcalá 202, Oaxaca, Oax., México", 17.0654, -96.7236),
            new Place("Unter den Linden 10, 10117 Berlin, Deutschland", 52.5170, 13.3889),
            new Place("Marienplatz 8, 80331 München, Deutschland", 48.1374, 11.5755),
            new Place("Jungfernstieg 7, 20354 Hamburg, Deutschland", 53.5530, 9.9925));

    /** Every whitespace-separated term must appear in the label, ignoring case. */
    public List<Place> search(String query) {
        String[] terms = query.toLowerCase(Locale.ROOT).trim().split("[\\s,]+");
        return PLACES.stream()
                .filter(p -> Arrays.stream(terms).allMatch(t -> p.label().toLowerCase(Locale.ROOT).contains(t)))
                .limit(5)
                .toList();
    }
}
