package com.orbitlink.signup.coverage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.orbitlink.signup.OrbitlinkProperties;
import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;

/**
 * The coverage model: the planet is tiled with H3 hexagons at one fixed resolution, and each
 * hexagon is the unit of capacity. Hexagons (rather than squares or lat/lng boxes) have one
 * kind of neighbour and roughly equal area everywhere, which keeps "capacity per cell" fair.
 */
@Component
public class CoverageGrid {

    /** Caps how many cells one map request can ask for: k=8 is 217 cells. */
    public static final int MAX_DISK_RADIUS = 8;

    private final H3Core h3;
    private final int resolution;

    @Autowired
    public CoverageGrid(OrbitlinkProperties props) {
        this(props.h3Resolution());
    }

    public CoverageGrid(int resolution) {
        try {
            this.h3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new UncheckedIOException("could not load the H3 native library", e);
        }
        this.resolution = resolution;
    }

    public int resolution() {
        return resolution;
    }

    public String cellFor(double lat, double lng) {
        return h3.latLngToCellAddress(lat, lng, resolution);
    }

    /** True only for valid cells at this grid's resolution, so callers can't address other grids. */
    public boolean isServiceCell(String cell) {
        // H3 parses the string as hex and throws on anything else, so check the shape first.
        return cell != null && cell.matches("[0-9a-f]{15}") && h3.isValidCell(cell) && h3.getResolution(cell) == resolution;
    }

    public List<String> disk(String cell, int k) {
        return h3.gridDisk(cell, Math.min(k, MAX_DISK_RADIUS));
    }

    public LatLng center(String cell) {
        return h3.cellToLatLng(cell);
    }

    /** The market (and so the deposit currency) is a property of the cell, decided at its centre. */
    public Market market(String cell) {
        LatLng c = center(cell);
        return Market.at(c.lat, c.lng);
    }
}
