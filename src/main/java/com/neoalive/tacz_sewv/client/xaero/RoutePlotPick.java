package com.neoalive.tacz_sewv.client.xaero;

import java.util.List;

import net.minecraft.core.BlockPos;

/** Screen-space node picking shared by route plot modes on the world map. */
public final class RoutePlotPick {

    private RoutePlotPick() {}

    public static int nearestNode(int mouseX, int mouseY, PathwayPlot.ScreenProject project,
                                  double pickPx, List<BlockPos> nodes) {
        if (nodes.isEmpty()) return -1;
        int best = -1;
        double bestDistSq = pickPx * pickPx;
        for (int i = 0; i < nodes.size(); i++) {
            int[] at = project.toScreen(nodes.get(i));
            double dx = mouseX - at[0];
            double dy = mouseY - at[1];
            double distSq = dx * dx + dy * dy;
            if (distSq <= bestDistSq) {
                bestDistSq = distSq;
                best = i;
            }
        }
        return best;
    }
}
