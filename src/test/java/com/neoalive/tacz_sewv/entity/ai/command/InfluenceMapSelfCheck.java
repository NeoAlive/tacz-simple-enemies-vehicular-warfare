package com.neoalive.tacz_sewv.entity.ai.command;

import java.util.ArrayList;
import java.util.List;

/**
 * Headless self-check for the influence map and {@link BattleField}. Run via
 * {@code ./gradlew selfCheckInfluence}.
 */
public final class InfluenceMapSelfCheck {

    private static final int FACTION_US = 0;
    private static final int FACTION_RU = 1;

    private static final double CELL = 12.0;
    private static final int MAX_CELLS = 256;
    private static final double MARGIN = 24.0;

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        opposingClustersCentroidsAndAxis();
        oneSidedGapOpenFlank();
        oneVsTwoEnemyPockets();
        hugeBoundingBoxStaysUnderCellCap();
        arrayReuseDoesNotGrowPastNeed();
        flankOffsetPerpendicularToAxis();

        System.out.println("command influence self-check: OK");
    }

    /** Two opposing clusters → centroids on the correct sides; enemy→us axis points at us. */
    private static void opposingClustersCentroidsAndAxis() {
        List<UnitPos> units = new ArrayList<>();
        // Friendly (US) around (0, -40)
        units.add(new UnitPos(1, FACTION_US, -5, -40));
        units.add(new UnitPos(2, FACTION_US, 5, -40));
        units.add(new UnitPos(3, FACTION_US, 0, -35));
        // Enemy (RU) around (0, 40)
        units.add(new UnitPos(10, FACTION_RU, -5, 40));
        units.add(new UnitPos(11, FACTION_RU, 5, 40));
        units.add(new UnitPos(12, FACTION_RU, 0, 35));

        BattleField bf = build(units, FACTION_US);
        assertTrue(bf.populated, "battlefield populated");
        assertNear(0.0, bf.friendlyCentroidX, 3.0, "friendly cx");
        assertNear(-38.0, bf.friendlyCentroidZ, 5.0, "friendly cz south");
        assertNear(0.0, bf.enemyCentroidX, 3.0, "enemy cx");
        assertNear(38.0, bf.enemyCentroidZ, 5.0, "enemy cz north");

        // Axis enemy→us should point roughly south (negative Z).
        assertTrue(bf.axisZ < -0.7, "axis points toward friendly (south), got az=" + bf.axisZ);
        assertTrue(Math.abs(bf.axisX) < 0.35, "axis mostly along Z, got ax=" + bf.axisX);
        assertNear(1.0, Math.sqrt(bf.axisX * bf.axisX + bf.axisZ * bf.axisZ), 1.0e-6, "axis unit");
    }

    /**
     * Main enemy pocket on the axis, plus a wing on the right (−X when looking south along
     * enemy→us). Left probe is cold → open; right probe hits the wing → closed.
     */
    private static void oneSidedGapOpenFlank() {
        List<UnitPos> units = new ArrayList<>();
        units.add(new UnitPos(1, FACTION_US, 0, -60));
        units.add(new UnitPos(2, FACTION_US, 5, -60));
        // Primary pocket around (0, 0) — stronger so it ranks first
        units.add(new UnitPos(10, FACTION_RU, -2, 0));
        units.add(new UnitPos(11, FACTION_RU, 2, 0));
        units.add(new UnitPos(12, FACTION_RU, 0, 2));
        units.add(new UnitPos(13, FACTION_RU, 0, -2));
        units.add(new UnitPos(14, FACTION_RU, 1, 1));
        // Wing on the west (−X) = right flank when axis points south
        units.add(new UnitPos(20, FACTION_RU, -48, 0));
        units.add(new UnitPos(21, FACTION_RU, -52, 2));
        units.add(new UnitPos(22, FACTION_RU, -50, -2));

        BattleField bf = build(units, FACTION_US);
        assertTrue(bf.populated, "flank case populated");
        assertTrue(bf.openFlankLeft, "left (+X) gap must be open flank");
        assertTrue(!bf.openFlankRight, "right (−X) wing must close that flank");
    }

    private static void oneVsTwoEnemyPockets() {
        // Single tight cluster → one pocket.
        List<UnitPos> one = new ArrayList<>();
        one.add(new UnitPos(1, FACTION_US, 0, -40));
        one.add(new UnitPos(2, FACTION_US, 4, -40));
        one.add(new UnitPos(10, FACTION_RU, -2, 40));
        one.add(new UnitPos(11, FACTION_RU, 0, 42));
        one.add(new UnitPos(12, FACTION_RU, 2, 40));
        BattleField single = build(one, FACTION_US);
        assertEq(1, single.pocketCount, "tight enemy cluster → one pocket");

        // Two clusters separated well beyond merge distance (~2.5 cells ≈ 30 blocks at cell=12).
        List<UnitPos> two = new ArrayList<>();
        two.add(new UnitPos(1, FACTION_US, 0, -50));
        two.add(new UnitPos(2, FACTION_US, 4, -50));
        // Pocket A near (−40, 40)
        two.add(new UnitPos(10, FACTION_RU, -42, 40));
        two.add(new UnitPos(11, FACTION_RU, -38, 42));
        two.add(new UnitPos(12, FACTION_RU, -40, 38));
        // Pocket B near (+40, 40)
        two.add(new UnitPos(20, FACTION_RU, 38, 40));
        two.add(new UnitPos(21, FACTION_RU, 42, 42));
        two.add(new UnitPos(22, FACTION_RU, 40, 38));
        BattleField split = build(two, FACTION_US);
        assertEq(2, split.pocketCount, "two separated enemy clusters → two pockets");
    }

    private static void hugeBoundingBoxStaysUnderCellCap() {
        List<UnitPos> units = new ArrayList<>();
        units.add(new UnitPos(1, FACTION_US, 0, 0));
        units.add(new UnitPos(2, FACTION_US, 10, 0));
        // Opponent almost a kilometer away — AABB would be huge at cell=12.
        units.add(new UnitPos(10, FACTION_RU, 900, 900));
        units.add(new UnitPos(11, FACTION_RU, 910, 900));

        InfluenceMap map = new InfluenceMap();
        BattleField bf = new BattleField();
        int cap = 64; // tight cap so downscale is forced
        map.rebuildAndDerive(bf, units, FACTION_US, CELL, cap, MARGIN);
        assertTrue(map.cellCount() <= cap,
                "cell count " + map.cellCount() + " must stay ≤ " + cap);
        assertTrue(map.cellSize() > CELL,
                "cell size must increase under cap pressure, got " + map.cellSize());
        assertTrue(bf.populated, "downscaled map still derives a battlefield");
    }

    private static void arrayReuseDoesNotGrowPastNeed() {
        InfluenceMap map = new InfluenceMap();
        BattleField bf = new BattleField();
        List<UnitPos> small = List.of(
                new UnitPos(1, FACTION_US, 0, 0),
                new UnitPos(2, FACTION_US, 5, 0),
                new UnitPos(10, FACTION_RU, 0, 40),
                new UnitPos(11, FACTION_RU, 5, 40)
        );
        map.rebuildAndDerive(bf, small, FACTION_US, CELL, MAX_CELLS, MARGIN);
        int firstCells = map.cellCount();
        assertTrue(firstCells > 0, "first rebuild has cells");

        // Same footprint again — must not allocate a larger logical grid.
        map.rebuildAndDerive(bf, small, FACTION_US, CELL, MAX_CELLS, MARGIN);
        assertEq(firstCells, map.cellCount(), "rebuild same AABB keeps same cell count");

        // chooseCellSize pure: oversized span raises cell.
        double chosen = InfluenceMap.chooseCellSize(2000, 2000, 12.0, 100);
        assertTrue(chosen > 12.0, "chooseCellSize raises cell for huge span");
        int w = (int) Math.ceil(2000 / chosen);
        int h = (int) Math.ceil(2000 / chosen);
        assertTrue(w * h <= 100, "chosen cell fits under cap");
    }

    /**
     * Flank mark offsets must be perpendicular to the enemy→us axis — never world ±X.
     * Uses the same {@link com.neoalive.tacz_sewv.map.BattleFieldMarker} helpers as the packet
     * packager so a drifted placement formula cannot hide behind the overlay.
     */
    private static void flankOffsetPerpendicularToAxis() {
        // Diagonal fight: friendlies SW, enemies NE → axis is not axis-aligned.
        List<UnitPos> units = new ArrayList<>();
        units.add(new UnitPos(1, FACTION_US, -40, 40));
        units.add(new UnitPos(2, FACTION_US, -35, 45));
        units.add(new UnitPos(3, FACTION_US, -45, 35));
        units.add(new UnitPos(10, FACTION_RU, 40, -40));
        units.add(new UnitPos(11, FACTION_RU, 35, -45));
        units.add(new UnitPos(12, FACTION_RU, 45, -35));

        BattleField bf = build(units, FACTION_US);
        assertTrue(bf.populated, "diagonal BF populated");
        assertTrue(Math.abs(bf.axisX) > 0.3 && Math.abs(bf.axisZ) > 0.3,
                "fixture axis must be diagonal, got (" + bf.axisX + "," + bf.axisZ + ")");

        double ax = bf.axisX;
        double az = bf.axisZ;
        double ex = bf.enemyCentroidX;
        double ez = bf.enemyCentroidZ;

        double lx = com.neoalive.tacz_sewv.map.BattleFieldMarker.flankMarkX(ex, ax, az, +1);
        double lz = com.neoalive.tacz_sewv.map.BattleFieldMarker.flankMarkZ(ez, ax, az, +1);
        double rx = com.neoalive.tacz_sewv.map.BattleFieldMarker.flankMarkX(ex, ax, az, -1);
        double rz = com.neoalive.tacz_sewv.map.BattleFieldMarker.flankMarkZ(ez, ax, az, -1);

        double oxL = lx - ex;
        double ozL = lz - ez;
        double oxR = rx - ex;
        double ozR = rz - ez;

        double dotL = oxL * ax + ozL * az;
        double dotR = oxR * ax + ozR * az;
        assertNear(0.0, dotL, 1.0e-9, "left flank offset ⟂ axis (dot=" + dotL + ")");
        assertNear(0.0, dotR, 1.0e-9, "right flank offset ⟂ axis (dot=" + dotR + ")");

        double lenL = Math.sqrt(oxL * oxL + ozL * ozL);
        double lenR = Math.sqrt(oxR * oxR + ozR * ozR);
        double off = com.neoalive.tacz_sewv.map.BattleFieldMarker.FLANK_MARK_OFFSET;
        assertNear(off, lenL, 1.0e-9, "left flank at FLANK_MARK_OFFSET");
        assertNear(off, lenR, 1.0e-9, "right flank at FLANK_MARK_OFFSET");

        // Not world-east/west: |ΔZ| must be comparable to |ΔX| on a diagonal axis.
        assertTrue(Math.abs(ozL) > Math.abs(oxL) * 0.4,
                "left flank must not collapse to world ±X (ox=" + oxL + " oz=" + ozL + ")");
        assertTrue(Math.abs(ozR) > Math.abs(oxR) * 0.4,
                "right flank must not collapse to world ±X (ox=" + oxR + " oz=" + ozR + ")");
    }

    private static BattleField build(List<UnitPos> units, int ourFaction) {
        InfluenceMap map = new InfluenceMap();
        BattleField bf = new BattleField();
        map.rebuildAndDerive(bf, units, ourFaction, CELL, MAX_CELLS, MARGIN);
        return bf;
    }

    private static void assertTrue(boolean cond, String message) {
        assert cond : message;
    }

    private static void assertEq(int expected, int actual, String label) {
        assert actual == expected : label + ": expected " + expected + " got " + actual;
    }

    private static void assertNear(double expected, double actual, double tol, String label) {
        assert Math.abs(actual - expected) <= tol
                : label + ": expected ~" + expected + " ±" + tol + " got " + actual;
    }
}
