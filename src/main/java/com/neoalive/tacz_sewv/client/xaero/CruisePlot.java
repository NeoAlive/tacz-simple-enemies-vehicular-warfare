package com.neoalive.tacz_sewv.client.xaero;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;

import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketPatrolVehicle;

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
        GuardPlot.cancel();
        PathwayPlot.cancel();
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

    public static void removeAtScreen(int mouseX, int mouseY, PathwayPlot.ScreenProject project,
                                      double pickPx) {
        if (NODES.isEmpty()) return;
        int drop = RoutePlotPick.nearestNode(mouseX, mouseY, project, pickPx, NODES);
        if (drop < 0) return;
        NODES.remove(drop);
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
