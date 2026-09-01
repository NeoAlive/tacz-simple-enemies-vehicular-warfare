package com.neoalive.tacz_sewv.client.xaero;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import com.neoalive.tacz_sewv.client.PreferredPathwaysClient;
import com.neoalive.tacz_sewv.map.PreferredPathwayData;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketPatrolVehicle;
import com.neoalive.tacz_sewv.network.PacketSavePreferredPathway;

/**
 * World-map plotting mode for persistent preferred pathways (Xaero only).
 */
public final class PathwayPlot {

    @FunctionalInterface
    public interface ScreenProject {
        int[] toScreen(BlockPos pos);
    }

    private static final List<BlockPos> NODES = new ArrayList<>();
    private static ResourceKey<Level> dimension;
    private static String pathId = "";
    private static boolean armed;
    private static boolean editing;
    /** Highlighted node for RMB delete; -1 when none. */
    private static int selectedIndex = -1;
    /** Node under cursor — visual only, updated each frame. */
    private static int hoverIndex = -1;

    private PathwayPlot() {}

    public static boolean arm(ResourceKey<Level> dim) {
        return armInternal(dim, suggestId(dim), List.of(), false);
    }

    /** Re-open an existing saved path for editing (same id overwrites on confirm). */
    public static boolean armEdit(ResourceKey<Level> dim, String id, List<BlockPos> existing) {
        if (existing == null || existing.isEmpty()) return false;
        return armInternal(dim, id, new ArrayList<>(existing), true);
    }

    private static boolean armInternal(ResourceKey<Level> dim, String id, List<BlockPos> seed,
                                       boolean edit) {
        CruisePlot.cancel();
        GuardPlot.cancel();
        dimension = dim;
        pathId = id;
        NODES.clear();
        NODES.addAll(seed);
        selectedIndex = -1;
        hoverIndex = -1;
        editing = edit;
        armed = true;
        return true;
    }

    public static boolean armed() {
        return armed;
    }

    public static boolean editing() {
        return editing;
    }

    public static List<BlockPos> nodes() {
        return NODES;
    }

    public static String pathId() {
        return pathId;
    }

    public static ResourceKey<Level> dimension() {
        return dimension;
    }

    public static int selectedIndex() {
        return selectedIndex;
    }

    public static int hoverIndex() {
        return hoverIndex;
    }

    public static boolean canConfirm() {
        return NODES.size() >= 2;
    }

    public static void updateHover(int mouseX, int mouseY, ScreenProject project, double pickPx) {
        hoverIndex = RoutePlotPick.nearestNode(mouseX, mouseY, project, pickPx, NODES);
    }

    public static void pickOrAddScreen(BlockPos node, int mouseX, int mouseY,
                                       ScreenProject project, double pickPx) {
        int near = RoutePlotPick.nearestNode(mouseX, mouseY, project, pickPx, NODES);
        if (near >= 0) {
            selectedIndex = near;
            return;
        }
        if (NODES.size() >= PacketPatrolVehicle.MAX_ROUTE_NODES) return;
        if (containsNode(node)) return;
        NODES.add(node);
        selectedIndex = NODES.size() - 1;
    }

    public static void removeAtScreen(int mouseX, int mouseY, ScreenProject project, double pickPx) {
        if (NODES.isEmpty()) return;
        int drop = RoutePlotPick.nearestNode(mouseX, mouseY, project, pickPx, NODES);
        if (drop < 0 && selectedIndex >= 0 && selectedIndex < NODES.size()) {
            drop = selectedIndex;
        }
        if (drop < 0) return;
        NODES.remove(drop);
        if (NODES.isEmpty()) {
            selectedIndex = -1;
        } else if (selectedIndex >= NODES.size()) {
            selectedIndex = NODES.size() - 1;
        } else if (selectedIndex == drop) {
            selectedIndex = Math.min(drop, NODES.size() - 1);
        }
        hoverIndex = -1;
    }

    public static void cancel() {
        armed = false;
        editing = false;
        dimension = null;
        pathId = "";
        NODES.clear();
        selectedIndex = -1;
        hoverIndex = -1;
    }

    /** @return nodes saved, or 0 if confirm failed (too few nodes). */
    public static int confirm() {
        int count = NODES.size();
        if (count >= 2 && dimension != null) {
            NetworkHandler.CHANNEL.sendToServer(
                    new PacketSavePreferredPathway(pathId, dimension, List.copyOf(NODES), false));
            cancel();
            return count;
        }
        return 0;
    }

    private static boolean containsNode(BlockPos node) {
        for (BlockPos existing : NODES) {
            if (existing.equals(node)) return true;
        }
        return false;
    }

    private static String suggestId(ResourceKey<Level> dim) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return "path_1";
        Map<String, List<BlockPos>> existing = PreferredPathwaysClient.forDimension(dim);
        for (int i = 1; i <= PreferredPathwayData.MAX_PATHS_PER_DIMENSION; i++) {
            String id = "path_" + i;
            if (!existing.containsKey(id)) return id;
        }
        return "path_1";
    }
}
