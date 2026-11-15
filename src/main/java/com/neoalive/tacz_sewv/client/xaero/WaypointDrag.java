package com.neoalive.tacz_sewv.client.xaero;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.client.map.MapProjection;
import com.neoalive.tacz_sewv.client.map.WaypointHandles;
import com.neoalive.tacz_sewv.map.MarkerOrder;
import com.neoalive.tacz_sewv.map.VehicleMarker;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketMoveWaypoint;

/**
 * Client half of dragging an order waypoint on the World Map. Press on a handle, move, release.
 * MOVE, PATROL and SEARCH update the server while held (gated by {@link WaypointHandles#shouldSend});
 * a cruise node is sent once on release. The drawn orders follow the cursor through
 * {@link MapMarkers#override}. Esc or a right-press puts the order back.
 */
public final class WaypointDrag {

    public static final double PICK_PX = 8.0;
    private static final double CLICK_PX = 4.0;

    @Nullable
    private static WaypointHandles.Handle handle;
    private static List<Integer> units = List.of();
    private static final Map<Integer, MarkerOrder> BASE = new HashMap<>();
    private static boolean detach;
    private static BlockPos current = BlockPos.ZERO;
    private static BlockPos lastSent = BlockPos.ZERO;
    private static long lastSentMs;
    private static boolean liveSent;
    private static boolean moved;
    private static double pressX;
    private static double pressY;

    private WaypointDrag() {}

    public static boolean active() {
        return handle != null;
    }

    /** Own units' orders in {@code dim}, as drawn (drag overrides included). */
    public static List<WaypointHandles.Entry> entries(ResourceKey<Level> dim) {
        List<WaypointHandles.Entry> out = new ArrayList<>();
        for (VehicleMarker m : MapMarkers.markers()) {
            if (m.allegiance() != VehicleMarker.Allegiance.OWN || m.driverId() == MapMarkers.NO_DRIVER) continue;
            if (!dim.equals(m.dimension())) continue;
            MarkerOrder o = MapMarkers.effectiveOrder(m);
            if (o.type() == MarkerOrder.Type.NONE) continue;
            out.add(new WaypointHandles.Entry(m.driverId(), o, MapMarkers.isSelected(m)));
        }
        return out;
    }

    public static List<WaypointHandles.Handle> handles(ResourceKey<Level> dim) {
        return WaypointHandles.list(entries(dim));
    }

    /** The handle under the cursor, or the one being dragged. */
    @Nullable
    public static WaypointHandles.Handle hovered(List<WaypointHandles.Handle> handles, double mx, double my,
                                                 MapProjection project) {
        return WaypointHandles.pick(handles, mx, my, project, PICK_PX);
    }

    /** Starts a drag if a handle is under the cursor. Answers whether the press was taken. */
    public static boolean begin(double mx, double my, MapProjection project, ResourceKey<Level> dim, boolean shift) {
        List<WaypointHandles.Entry> entries = entries(dim);
        WaypointHandles.Handle h = WaypointHandles.pick(WaypointHandles.list(entries), mx, my, project, PICK_PX);
        if (h == null) return false;
        handle = h;
        detach = shift;
        units = WaypointHandles.dragUnits(entries, h, shift);
        BASE.clear();
        for (WaypointHandles.Entry e : entries) {
            if (units.contains(e.unitId())) BASE.put(e.unitId(), e.order());
        }
        current = h.point();
        lastSent = h.point();
        lastSentMs = System.currentTimeMillis();
        liveSent = false;
        moved = false;
        pressX = mx;
        pressY = my;
        return true;
    }

    /** Per frame while held: follow the cursor and send a live update when the gate allows. */
    public static void sample(double mx, double my, BlockPos cursor) {
        WaypointHandles.Handle h = handle;
        if (h == null) return;
        if (!moved && Math.hypot(mx - pressX, my - pressY) < CLICK_PX) return;
        moved = true;
        current = new BlockPos(cursor.getX(), cursor.getY(), cursor.getZ());
        preview(true);
        long now = System.currentTimeMillis();
        if (WaypointHandles.live(h.kind()) && WaypointHandles.shouldSend(now, lastSentMs, lastSent, current)) {
            send(current, false);
            lastSent = current;
            lastSentMs = now;
            liveSent = true;
        }
    }

    public static void release(double mx, double my, BlockPos cursor) {
        WaypointHandles.Handle h = handle;
        if (h == null) return;
        sample(mx, my, cursor);
        if (!moved) {
            reset();
            return;
        }
        send(current, true);
        preview(false);
        hint("message.tacz_sewv.waypoint.moved", units.size());
        reset();
    }

    /** Abandons the drag and puts the order back where it was. */
    public static void cancel() {
        WaypointHandles.Handle h = handle;
        if (h == null) return;
        if (liveSent) send(h.point(), true);
        for (int id : units) MapMarkers.dropOverride(id);
        if (moved) hint("message.tacz_sewv.waypoint.cancelled");
        reset();
    }

    private static void preview(boolean pinned) {
        WaypointHandles.Handle h = handle;
        if (h == null) return;
        int dx = current.getX() - h.point().getX();
        int dz = current.getZ() - h.point().getZ();
        for (Map.Entry<Integer, MarkerOrder> e : BASE.entrySet()) {
            MapMarkers.override(e.getKey(), WaypointHandles.shift(e.getValue(), h.node(), dx, dz), pinned);
        }
    }

    private static void send(BlockPos to, boolean fin) {
        WaypointHandles.Handle h = handle;
        if (h == null) return;
        NetworkHandler.CHANNEL.sendToServer(new PacketMoveWaypoint(h.kind(), h.batch(), units, h.node(), to, fin,
                detach && fin));
    }

    private static void reset() {
        handle = null;
        units = List.of();
        BASE.clear();
        moved = false;
        liveSent = false;
    }

    private static void hint(String key, Object... args) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable(key, args).withStyle(ChatFormatting.GREEN), true);
        }
    }
}
