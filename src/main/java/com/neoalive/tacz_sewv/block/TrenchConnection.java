package com.neoalive.tacz_sewv.block;

import net.minecraft.util.StringRepresentable;

/**
 * Absolute trench topology for one cell. Encodes both which neighbors connect and which
 * model/rotation to use — mirrors {@code end_left}/{@code end_right} stay distinct entries
 * rather than being derived from each other by a 180° spin.
 *
 * <p>The {@code +} junction mesh lives on the separate {@code trench_x_cross} block and is
 * never selected here — four cardinal neighbors always resolve to {@link #PLINTH}.
 */
public enum TrenchConnection implements StringRepresentable {
    LONE("lone"),

    /** Open north — {@code end_right} @ y=90. */
    END_NORTH("end_north"),
    /** Open east — {@code end_left} @ y=0. */
    END_EAST("end_east"),
    /** Open south — {@code end_left} @ y=90. */
    END_SOUTH("end_south"),
    /** Open west — {@code end_right} @ y=0. */
    END_WEST("end_west"),

    MID_NS("mid_ns"),
    MID_EW("mid_ew"),

    CORNER_NE("corner_ne"),
    CORNER_NW("corner_nw"),
    CORNER_SE("corner_se"),
    CORNER_SW("corner_sw"),

    /** Banded (walled) side named — open on the other three. */
    TCROSS_NORTH("tcross_north"),
    TCROSS_EAST("tcross_east"),
    TCROSS_SOUTH("tcross_south"),
    TCROSS_WEST("tcross_west"),

    /** Floor only — all four cardinals present. No auto {@code +} junction. */
    PLINTH("plinth");

    private final String name;

    TrenchConnection(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    public static TrenchConnection fromNeighbors(boolean north, boolean east, boolean south, boolean west) {
        int count = (north ? 1 : 0) + (east ? 1 : 0) + (south ? 1 : 0) + (west ? 1 : 0);
        return switch (count) {
            case 0 -> LONE;
            case 1 -> {
                if (north) yield END_NORTH;
                if (east) yield END_EAST;
                if (south) yield END_SOUTH;
                yield END_WEST;
            }
            case 2 -> {
                if (north && south) yield MID_NS;
                if (east && west) yield MID_EW;
                if (north && east) yield CORNER_NE;
                if (north && west) yield CORNER_NW;
                if (south && east) yield CORNER_SE;
                yield CORNER_SW;
            }
            case 3 -> {
                if (!north) yield TCROSS_NORTH;
                if (!east) yield TCROSS_EAST;
                if (!south) yield TCROSS_SOUTH;
                yield TCROSS_WEST;
            }
            default -> PLINTH;
        };
    }

    public boolean wallNorth() {
        return switch (this) {
            case LONE, END_EAST, END_SOUTH, END_WEST, MID_EW, CORNER_SE, CORNER_SW, TCROSS_NORTH -> true;
            default -> false;
        };
    }

    public boolean wallEast() {
        return switch (this) {
            case LONE, END_NORTH, END_SOUTH, END_WEST, MID_NS, CORNER_NW, CORNER_SW, TCROSS_EAST -> true;
            default -> false;
        };
    }

    public boolean wallSouth() {
        return switch (this) {
            case LONE, END_NORTH, END_EAST, END_WEST, MID_EW, CORNER_NE, CORNER_NW, TCROSS_SOUTH -> true;
            default -> false;
        };
    }

    public boolean wallWest() {
        return switch (this) {
            case LONE, END_NORTH, END_EAST, END_SOUTH, MID_NS, CORNER_NE, CORNER_SE, TCROSS_WEST -> true;
            default -> false;
        };
    }

    public boolean hasWall(net.minecraft.core.Direction face) {
        return switch (face) {
            case NORTH -> wallNorth();
            case EAST -> wallEast();
            case SOUTH -> wallSouth();
            case WEST -> wallWest();
            default -> false;
        };
    }

    /** Axe-cut: end caps open into a mid; any walled face digs out to plinth. */
    public TrenchConnection axeConvert(net.minecraft.core.Direction clickedFace) {
        return switch (this) {
            case END_NORTH, END_SOUTH -> MID_NS;
            case END_EAST, END_WEST -> MID_EW;
            case PLINTH -> null;
            default -> hasWall(clickedFace) ? PLINTH : null;
        };
    }

    public static void main(String[] args) {
        assert fromNeighbors(false, true, true, true) == TCROSS_NORTH;
        assert fromNeighbors(true, false, true, true) == TCROSS_EAST;
        assert fromNeighbors(true, true, false, true) == TCROSS_SOUTH;
        assert fromNeighbors(true, true, true, false) == TCROSS_WEST;
        assert fromNeighbors(false, true, true, false) == CORNER_SE;
        assert fromNeighbors(true, true, false, false) == CORNER_NE;
        assert fromNeighbors(true, false, true, false) == MID_NS;
        assert fromNeighbors(false, true, false, true) == MID_EW;
        assert fromNeighbors(false, false, false, false) == LONE;
        assert fromNeighbors(true, true, true, true) == PLINTH;
        assert !PLINTH.wallNorth() && !PLINTH.wallEast();
        System.out.println("TrenchConnection self-check OK");
    }
}
