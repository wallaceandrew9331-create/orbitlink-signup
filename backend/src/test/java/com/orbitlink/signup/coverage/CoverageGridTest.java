package com.orbitlink.signup.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.uber.h3core.H3Core;

class CoverageGridTest {

    private final CoverageGrid grid = new CoverageGrid(5);

    @Test
    void nearbyPointsShareACellAndDistantOnesDont() {
        String beale = grid.cellFor(35.1395, -90.0526);
        assertThat(grid.cellFor(35.1400, -90.0520)).isEqualTo(beale);
        assertThat(grid.cellFor(30.2700, -97.7431)).isNotEqualTo(beale);
    }

    @Test
    void onlyCellsAtTheGridResolutionAreServiceCells() throws Exception {
        String cell = grid.cellFor(35.1395, -90.0526);
        String finer = H3Core.newInstance().latLngToCellAddress(35.1395, -90.0526, 9);
        assertThat(grid.isServiceCell(cell)).isTrue();
        assertThat(grid.isServiceCell(finer)).isFalse();
        assertThat(grid.isServiceCell("not-a-cell")).isFalse();
        assertThat(grid.isServiceCell(null)).isFalse();
    }

    @Test
    void diskHasHexagonalRingCountsAndIsCapped() {
        String cell = grid.cellFor(35.1395, -90.0526);
        assertThat(grid.disk(cell, 1)).hasSize(7);       // 1 + 6
        assertThat(grid.disk(cell, 2)).hasSize(19);      // 1 + 6 + 12
        assertThat(grid.disk(cell, 50)).hasSize(grid.disk(cell, CoverageGrid.MAX_DISK_RADIUS).size());
    }

    @Test
    void marketIsDecidedByTheCellCentre() {
        assertThat(grid.market(grid.cellFor(25.6866, -100.3161))).isEqualTo(Market.MX);
        assertThat(grid.market(grid.cellFor(35.1395, -90.0526))).isEqualTo(Market.US);
    }
}
