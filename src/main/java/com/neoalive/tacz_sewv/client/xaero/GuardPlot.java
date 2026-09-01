package com.neoalive.tacz_sewv.client.xaero;

import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketSetGuardPosition;

/**
 * World-map GUARD_POSITION plot: one BlockPos for the captured selection. Sibling of
 * {@link CruisePlot}; Confirm sends {@link PacketSetGuardPosition}, Cancel is client-only.
 */
public final class GuardPlot {

    @Nullable
    private static BlockPos point;
    private static List<Integer> crews = List.of();
    private static boolean armed;

    private GuardPlot() {}

    public static boolean arm() {
        Set<Integer> selected = MapMarkers.selected();
        if (selected.isEmpty()) return false;
        CruisePlot.cancel();
        PathwayPlot.cancel();
        crews = List.copyOf(selected);
        point = null;
        armed = true;
        return true;
    }

    public static boolean armed() {
        return armed;
    }

    @Nullable
    public static BlockPos point() {
        return point;
    }

    public static void set(BlockPos pos) {
        point = pos;
    }

    public static void clearPoint() {
        point = null;
    }

    public static void cancel() {
        armed = false;
        crews = List.of();
        point = null;
    }

    /** Send the plotted guard point. Answers 1 if sent, 0 if nothing to send. */
    public static int confirm() {
        int count = 0;
        if (point != null && !crews.isEmpty()) {
            NetworkHandler.CHANNEL.sendToServer(new PacketSetGuardPosition(crews, point));
            count = 1;
        }
        cancel();
        return count;
    }
}
