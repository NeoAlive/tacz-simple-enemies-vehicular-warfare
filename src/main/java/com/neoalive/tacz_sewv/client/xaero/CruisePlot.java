package com.neoalive.tacz_sewv.client.xaero;

import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketPatrolVehicle;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The world map's cruise plotting mode: while it is armed, clicks on the map lay out a route
 * instead of selecting, and Confirm sends it.
 *
 * <p>Client-only and deliberately dumb — a list of nodes and the crews they are for. Everything
 * that acts on it lives in {@code MixinGuiMap} (clicks, drawing, the two buttons), because the map
 * screen is the only place any of it means anything. The crews are captured when the mode is armed
 * rather than read at Confirm, so deselecting or a marker sync mid-plot cannot quietly change who
 * the route is for.
 *
 * <p>State is static and survives the screen: closing the map with a plot half-drawn and opening it
 * again continues it, which is the same reasoning that made the terminal's steppers static.
 */
public final class CruisePlot {

    private static final List<BlockPos> NODES = new ArrayList<>();
    private static List<Integer> crews = List.of();
    private static boolean armed;

    private CruisePlot() {}

    /** Enter plotting mode for the current selection. No selection, no mode. */
    public static boolean arm() {
        Set<Integer> selected = MapMarkers.selected();
        if (selected.isEmpty()) return false;
        crews = List.copyOf(selected);
        NODES.clear();
        armed = true;
        return true;
    }

    public static boolean armed() {
        return armed;
    }

    public static List<BlockPos> nodes() {
        return NODES;
    }

    public static void add(BlockPos node) {
        if (NODES.size() < PacketPatrolVehicle.MAX_ROUTE_NODES) NODES.add(node);
    }

    /**
     * Drop the node nearest {@code x,z} within {@code reach} blocks, falling back to the last one
     * laid — a right-click that misses everything reads as undo rather than as nothing.
     */
    public static void removeNear(double x, double z, double reach) {
        if (NODES.isEmpty()) return;
        int best = NODES.size() - 1;
        double bestDistSq = reach * reach;
        for (int i = 0; i < NODES.size(); i++) {
            BlockPos node = NODES.get(i);
            double dx = node.getX() + 0.5 - x;
            double dz = node.getZ() + 0.5 - z;
            double distSq = dx * dx + dz * dz;
            if (distSq <= bestDistSq) {
                bestDistSq = distSq;
                best = i;
            }
        }
        NODES.remove(best);
    }

    public static void cancel() {
        armed = false;
        crews = List.of();
        NODES.clear();
    }

    /** Send the plotted route to the captured crews. Answers how many nodes went out. */
    public static int confirm() {
        int count = NODES.size();
        if (count > 0 && !crews.isEmpty()) {
            NetworkHandler.CHANNEL.sendToServer(PacketPatrolVehicle.cruise(crews, List.copyOf(NODES)));
        }
        cancel();
        return count;
    }
}
