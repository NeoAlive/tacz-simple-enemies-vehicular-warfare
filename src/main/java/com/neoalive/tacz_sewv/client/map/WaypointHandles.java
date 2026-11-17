package com.neoalive.tacz_sewv.client.map;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;

import com.neoalive.tacz_sewv.map.MarkerOrder;

/**
 * Which order waypoints the map offers as draggable handles, and the gates of a drag. Pure: takes
 * plain entries rather than markers, so it runs headless in {@code selfCheckWaypointDrag}.
 *
 * <p>One handle per distinct point of a batch, so units sharing a point share a handle. A MOVE point
 * any of whose units has arrived offers none. Cruise nodes are offered only while one of the crews
 * is selected, since a long route would otherwise cover the map in squares.
 */
public final class WaypointHandles {

    /** Live sends: no more often than this, and only after the point has moved this far. */
    public static final long SEND_GAP_MS = 250L;
    public static final double SEND_MIN_BLOCKS = 2.0;

    public record Entry(int unitId, MarkerOrder order, boolean selected) {}

    /** {@code node} is the route index for a cruise node, -1 otherwise. */
    public record Handle(MarkerOrder.Type kind, long batch, int node, BlockPos point, List<Integer> onPoint) {}

    private record Key(MarkerOrder.Type kind, long batch, int node, long point) {}

    private WaypointHandles() {}

    public static List<Handle> list(List<Entry> entries) {
        Map<Key, List<Integer>> points = new LinkedHashMap<>();
        Set<Key> blocked = new HashSet<>();
        Set<Long> selectedCruise = new HashSet<>();
        for (Entry e : entries) {
            if (e.selected() && e.order().type() == MarkerOrder.Type.CRUISE) selectedCruise.add(e.order().batch());
        }
        for (Entry e : entries) {
            MarkerOrder o = e.order();
            switch (o.type()) {
                case MOVE -> {
                    Key k = new Key(o.type(), o.batch(), -1, o.target().asLong());
                    if (o.reached()) blocked.add(k);
                    points.computeIfAbsent(k, x -> new ArrayList<>()).add(e.unitId());
                }
                case PATROL, SEARCH -> points.computeIfAbsent(
                        new Key(o.type(), o.batch(), -1, o.target().asLong()), x -> new ArrayList<>()).add(e.unitId());
                case CRUISE -> {
                    if (!selectedCruise.contains(o.batch())) continue;
                    for (int i = 0; i < o.route().size(); i++) {
                        points.computeIfAbsent(new Key(o.type(), o.batch(), i, o.route().get(i).asLong()),
                                x -> new ArrayList<>()).add(e.unitId());
                    }
                }
                case NONE -> { }
            }
        }
        List<Handle> out = new ArrayList<>();
        for (Map.Entry<Key, List<Integer>> p : points.entrySet()) {
            Key k = p.getKey();
            if (blocked.contains(k)) continue;
            out.add(new Handle(k.kind(), k.batch(), k.node(), BlockPos.of(k.point()), List.copyOf(p.getValue())));
        }
        return out;
    }

    /** Every unit a drag of {@code h} moves: the whole batch, or only the units on its point when detaching. */
    public static List<Integer> dragUnits(List<Entry> entries, Handle h, boolean detach) {
        if (detach) return h.onPoint();
        List<Integer> out = new ArrayList<>(h.onPoint());
        for (Entry e : entries) {
            MarkerOrder o = e.order();
            if (o.type() == h.kind() && o.batch() == h.batch() && !out.contains(e.unitId())) out.add(e.unitId());
        }
        return out;
    }

    /** The handle nearest the cursor within {@code pickPx}, or null. */
    @Nullable
    public static Handle pick(List<Handle> handles, double mouseX, double mouseY, MapProjection project,
                              double pickPx) {
        Handle best = null;
        double bestSq = pickPx * pickPx;
        for (Handle h : handles) {
            int[] at = project.toScreen(h.point());
            double dx = mouseX - at[0];
            double dy = mouseY - at[1];
            double d = dx * dx + dy * dy;
            if (d <= bestSq) {
                bestSq = d;
                best = h;
            }
        }
        return best;
    }

    /** The live-send gate: time since the last send and distance from the last sent point. */
    public static boolean shouldSend(long nowMs, long lastMs, BlockPos lastSent, BlockPos current) {
        if (nowMs - lastMs < SEND_GAP_MS) return false;
        double dx = current.getX() - lastSent.getX();
        double dz = current.getZ() - lastSent.getZ();
        return dx * dx + dz * dz >= SEND_MIN_BLOCKS * SEND_MIN_BLOCKS;
    }

    /** Whether a drag of this kind updates the server while the button is still held. */
    public static boolean live(MarkerOrder.Type kind) {
        return kind == MarkerOrder.Type.MOVE || kind == MarkerOrder.Type.PATROL || kind == MarkerOrder.Type.SEARCH;
    }

    /** {@code o} with its point moved by the drag delta, keeping its offset from the dragged handle. */
    public static MarkerOrder shift(MarkerOrder o, int node, int dx, int dz) {
        BlockPos p = o.point(node);
        return o.shifted(node, new BlockPos(p.getX() + dx, p.getY(), p.getZ() + dz));
    }

    public static boolean same(Handle a, @Nullable Handle b) {
        return b != null && a.kind() == b.kind() && a.batch() == b.batch() && a.node() == b.node()
                && Objects.equals(a.point(), b.point());
    }
}
