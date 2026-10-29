package com.orbitlink.signup.coverage;

/**
 * Where a cell is sold decides the deposit and its currency. Amounts are in minor units
 * (cents) so money never touches floating point.
 */
public enum Market {
    US("USD", 9_900),
    MX("MXN", 179_900),
    DE("EUR", 9_900),
    INTL("USD", 9_900);

    private final String currency;
    private final long depositMinor;

    Market(String currency, long depositMinor) {
        this.currency = currency;
        this.depositMinor = depositMinor;
    }

    public String currency() {
        return currency;
    }

    public long depositMinor() {
        return depositMinor;
    }

    // A deliberately coarse stand-in for real reverse geocoding: a rough outline of Mexico,
    // then bounding boxes. Good enough to show per-market pricing; not good enough for tax.
    private static final double[][] MEXICO = {
            {-117.1, 32.6}, {-114.7, 32.7}, {-111.0, 31.3}, {-108.2, 31.3}, {-108.2, 31.8},
            {-106.5, 31.8}, {-104.5, 29.6}, {-101.4, 29.8}, {-99.5, 27.5}, {-97.1, 25.9},
            {-97.0, 21.0}, {-94.5, 18.2}, {-90.4, 21.5}, {-86.7, 21.5}, {-88.2, 17.8},
            {-91.4, 17.3}, {-92.2, 14.5}, {-98.0, 16.0}, {-105.5, 20.0}, {-110.0, 22.8},
            {-115.0, 28.0}, {-117.3, 32.0}};

    public static Market at(double lat, double lng) {
        if (insidePolygon(lng, lat, MEXICO)) {
            return MX;
        }
        if (lat >= 24.5 && lat <= 49.4 && lng >= -125.0 && lng <= -66.9) {
            return US;
        }
        if (lat >= 47.2 && lat <= 55.1 && lng >= 5.8 && lng <= 15.1) {
            return DE;
        }
        return INTL;
    }

    /** Ray casting: count how many polygon edges a ray going east from the point crosses. */
    static boolean insidePolygon(double x, double y, double[][] polygon) {
        boolean inside = false;
        for (int i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
            double xi = polygon[i][0], yi = polygon[i][1];
            double xj = polygon[j][0], yj = polygon[j][1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }
}
