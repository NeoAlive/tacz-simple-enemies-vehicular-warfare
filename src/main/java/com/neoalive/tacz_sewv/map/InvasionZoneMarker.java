package com.neoalive.tacz_sewv.map;

import net.minecraft.core.BlockPos;

/**
 * One invasion match node for the world-map layer — built client-side from
 * {@link com.neoalive.tacz_sewv.invasion.InvasionHud.Snapshot}.
 */
public record InvasionZoneMarker(
        int index,
        byte kind,
        BlockPos pos,
        byte ownerSide,
        byte conquerSide,
        float progress,
        boolean capturing,
        int colorA,
        int colorB,
        int colorNeutral,
        String teamA,
        String teamB) {

    public double x() {
        return pos.getX() + 0.5;
    }

    public double y() {
        return pos.getY() + 0.5;
    }

    public double z() {
        return pos.getZ() + 0.5;
    }

    public boolean isBase() {
        return kind == com.neoalive.tacz_sewv.invasion.InvasionHud.KIND_BASE;
    }

    public int ownerArgb() {
        return 0xFF000000 | sideRgb(ownerSide);
    }

    public int conquerArgb() {
        return 0xFF000000 | sideRgb(conquerSide);
    }

    private int sideRgb(byte side) {
        return switch (side) {
            case com.neoalive.tacz_sewv.invasion.InvasionHud.SIDE_A -> colorA;
            case com.neoalive.tacz_sewv.invasion.InvasionHud.SIDE_B -> colorB;
            default -> colorNeutral;
        };
    }
}
