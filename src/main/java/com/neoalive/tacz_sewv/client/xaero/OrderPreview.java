package com.neoalive.tacz_sewv.client.xaero;

import com.neoalive.tacz_sewv.client.InvasionHudClient;
import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.util.VehicleMarker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.network.ModNetworking;
import net.nekoyuni.SimpleEnemyMod.network.packets.PacketIssueOrder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The "previewOrder" toolkit: the low-opacity, animated overlay that shows an order on the world map,
 * plus the multi-unit line-order dispatch built on top of it.
 *
 * <p><b>No Xaero type appears here on purpose.</b> The drawing methods take screen pixels the caller
 * ({@code MixinGuiMap}) has already projected from world coordinates, so this stays a plain
 * {@code GuiGraphics} painter. It carries no state of its own either — every overlay is drawn straight
 * from the server-synced {@link com.neoalive.tacz_sewv.util.MarkerOrder} on each marker, or from the
 * live drag the mixin owns, so an order that is dismissed or overridden simply stops being drawn on
 * the next sync. There is nothing here to leave stuck.
 */
public final class OrderPreview {

    private OrderPreview() {}

    /** Overlay opacity — deliberately faint, so a tasking picture never fights the map under it. */
    private static final int PREVIEW_ALPHA = 0x99;
    private static final double DASH_LEN = 4.0;
    private static final double DASH_GAP = 4.0;
    /**
     * Pixels per second the dashes march along a line. Kept slow on purpose: with many units under
     * orders at once the overlay reads as a calm tasking picture rather than a wall of racing dashes.
     */
    private static final double MARCH_PXPS = 8.0;
    /** Blink period of an order's target pip. Long, so a screenful of them pulses gently, not strobes. */
    private static final long BLINK_PERIOD_MS = 2000L;
    /** Seconds for an area ring to turn once. Slow — a rotating dashed circle should barely drift. */
    private static final double RING_SECONDS_PER_RAD = 2.4;
    private static final int MAX_RING_SEGMENTS = 64;

    public static int lowAlpha(int color) {
        return (color & 0x00FFFFFF) | (PREVIEW_ALPHA << 24);
    }

    /** An animated segmented line between two screen points — the marching "route" look. */
    public static void dashedLine(GuiGraphics g, double x1, double y1, double x2, double y2, int argb) {
        double dx = x2 - x1, dy = y2 - y1;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0) return;
        double ux = dx / len, uy = dy / len;
        double period = DASH_LEN + DASH_GAP;
        double march = (System.currentTimeMillis() / 1000.0 * MARCH_PXPS) % period;
        for (double d = -march; d < len; d += period) {
            double s = Math.max(0.0, d);
            double e = Math.min(len, d + DASH_LEN);
            for (double t = s; t < e; t += 2.0) {
                int px = (int) Math.round(x1 + ux * t);
                int py = (int) Math.round(y1 + uy * t);
                g.fill(px - 1, py - 1, px + 1, py + 1, argb);
            }
        }
    }

    /** A pulsing pip at an order's target — the "blinking dot on the MOVE block". */
    public static void blinkingDot(GuiGraphics g, int x, int y, int color) {
        int alpha = pulseAlpha(40, 230);
        g.fill(x - 3, y - 3, x + 3, y + 3, 0x50000000);
        g.fill(x - 2, y - 2, x + 2, y + 2, (color & 0x00FFFFFF) | (alpha << 24));
    }

    /**
     * Subtle wall-clock opacity pulse (period {@link #BLINK_PERIOD_MS}). Used by order pips and the
     * Sweep &amp; Advance hatch so animation stays consistent across map overlays.
     */
    public static int pulseAlpha(int min, int max) {
        double t = (System.currentTimeMillis() % BLINK_PERIOD_MS) / (double) BLINK_PERIOD_MS;
        return Mth.clamp((int) (min + (max - min) * (0.5 + 0.5 * Math.sin(t * Math.PI * 2.0))), min, max);
    }

    /** Apply {@link #pulseAlpha} to an opaque ARGB colour's alpha channel. */
    public static int withPulse(int opaqueArgb, int minAlpha, int maxAlpha) {
        return (opaqueArgb & 0x00FFFFFF) | (pulseAlpha(minAlpha, maxAlpha) << 24);
    }

    /** A static pip — a route node, or a unit's destination in the drag preview. */
    public static void node(GuiGraphics g, int x, int y, int argb) {
        g.fill(x - 3, y - 3, x + 3, y + 3, 0x60000000);
        g.fill(x - 2, y - 2, x + 2, y + 2, argb);
    }

    /** A slowly rotating dashed ring — the ground a PATROL / SEARCH area task covers. */
    public static void ring(GuiGraphics g, int cx, int cy, double r, int argb) {
        if (r < 2.0) {
            g.fill(cx - 1, cy - 1, cx + 1, cy + 1, argb);
            return;
        }
        int segs = Mth.clamp((int) (r * 0.9), 48, MAX_RING_SEGMENTS);
        double march = System.currentTimeMillis() / 1000.0 / RING_SECONDS_PER_RAD;
        for (int i = 0; i < segs; i++) {
            if ((i & 1) == 0) continue; // every other segment → dashed
            double ang = march + i * (Math.PI * 2.0 / segs);
            int px = (int) Math.round(cx + Math.cos(ang) * r);
            int py = (int) Math.round(cy + Math.sin(ang) * r);
            g.fill(px - 1, py - 1, px + 1, py + 1, argb);
        }
    }

    /**
     * Evenly spaced points from {@code a} to {@code b}, endpoints included. For a straight segment
     * this uniform parameterization <b>is</b> the arc-length parameterization (constant speed along
     * the line), so {@code n} units land at equal ground spacing — the count sets the density, the
     * segment sets the covered ground.
     */
    public static List<Vec3> arcLengthPoints(Vec3 a, Vec3 b, int n) {
        List<Vec3> pts = new ArrayList<>(Math.max(1, n));
        if (n <= 1) {
            pts.add(a.add(b).scale(0.5));
            return pts;
        }
        for (int i = 0; i < n; i++) {
            double t = (double) i / (n - 1);
            pts.add(new Vec3(Mth.lerp(t, a.x, b.x), Mth.lerp(t, a.y, b.y), Mth.lerp(t, a.z, b.z)));
        }
        return pts;
    }

    /**
     * Dispatch the selected units into a line from {@code a} to {@code b}: one MOVE_TO_POSITION each,
     * spaced by {@link #arcLengthPoints}. Units are matched to points by where they currently stand
     * (projected onto the line), so a squad files into the formation without crossing over itself.
     * Rides SEM's own {@code PacketIssueOrder}, exactly like the single-target MOVE order does.
     */
    public static void dispatchMoveLine(Vec3 a, Vec3 b) {
        if (InvasionHudClient.isActive()) {
            hint("message.tacz_sewv.invasion.orders_locked");
            return;
        }
        Set<Integer> selected = MapMarkers.selected();
        if (selected.size() < 2) return;

        Vec3 axis = b.subtract(a);
        Map<Integer, Vec3> pos = positions();
        List<Integer> drivers = new ArrayList<>(selected);
        drivers.sort(Comparator.comparingDouble(id -> projection(pos.get(id), a, axis)));

        List<Vec3> points = arcLengthPoints(a, b, drivers.size());
        for (int i = 0; i < drivers.size(); i++) {
            ModNetworking.CHANNEL.sendToServer(
                    new PacketIssueOrder(drivers.get(i), OrderType.MOVE_TO_POSITION, points.get(i), 0, -1));
        }
        MapMarkers.clearSelection();
        hint("message.tacz_sewv.map.ordered", drivers.size());
    }

    /** Where along the A→B axis a unit currently stands (unknown position sorts to the front). */
    private static double projection(Vec3 p, Vec3 a, Vec3 axis) {
        if (p == null) return 0.0;
        return (p.x - a.x) * axis.x + (p.z - a.z) * axis.z;
    }

    private static Map<Integer, Vec3> positions() {
        Map<Integer, Vec3> map = new HashMap<>();
        for (VehicleMarker m : MapMarkers.markers()) {
            map.put(m.driverId(), new Vec3(m.x(), m.y(), m.z()));
        }
        return map;
    }

    private static void hint(String key, Object... args) {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(key, args).withStyle(ChatFormatting.GREEN), true);
        }
    }
}
