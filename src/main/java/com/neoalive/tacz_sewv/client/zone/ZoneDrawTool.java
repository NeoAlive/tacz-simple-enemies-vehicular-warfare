package com.neoalive.tacz_sewv.client.zone;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import com.neoalive.tacz_sewv.entity.ai.support.FrontlineMath;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketZoneEdit;
import com.neoalive.tacz_sewv.zone.ZoneGeometry;

/**
 * Operator brush for the zone map. A left-drag collects chunks into one stroke, the release sends
 * it. The server checks operator status again, so this is a convenience gate, not the real one.
 */
public final class ZoneDrawTool {

    public enum Brush {
        OFF(0),
        PRIORITY(ZoneViewClient.PRIORITY),
        NOZONE(ZoneViewClient.MANUAL_NOZONE),
        ERASE(0);

        public final byte previewKind;

        Brush(int previewKind) {
            this.previewKind = (byte) previewKind;
        }
    }

    private static final int[] SIZES = {1, 3, 5, 9};

    private static Brush brush = Brush.OFF;
    private static int sizeIndex;
    private static boolean stroking;
    private static double lastX, lastZ;
    private static final LongLinkedOpenHashSet STROKE = new LongLinkedOpenHashSet();

    private ZoneDrawTool() {}

    public static boolean allowed() {
        Minecraft mc = Minecraft.getInstance();
        return ZoneViewClient.active() && mc.player != null && mc.player.hasPermissions(2);
    }

    public static boolean armed() {
        return brush != Brush.OFF && allowed();
    }

    public static Brush brush() {
        return brush;
    }

    public static void select(Brush b) {
        brush = brush == b ? Brush.OFF : b;
        cancelStroke();
    }

    public static int size() {
        return SIZES[sizeIndex];
    }

    public static void cycleSize() {
        sizeIndex = (sizeIndex + 1) % SIZES.length;
    }

    public static boolean stroking() {
        return stroking;
    }

    public static LongLinkedOpenHashSet stroke() {
        return STROKE;
    }

    public static void begin(double worldX, double worldZ) {
        stroking = true;
        STROKE.clear();
        lastX = worldX;
        lastZ = worldZ;
        stamp(worldX, worldZ, worldX, worldZ);
    }

    /** Called every frame while the button is held: adds the chunks crossed since the last sample. */
    public static void sample(double worldX, double worldZ) {
        if (!stroking) return;
        if (worldX == lastX && worldZ == lastZ) return;
        stamp(lastX, lastZ, worldX, worldZ);
        lastX = worldX;
        lastZ = worldZ;
    }

    private static void stamp(double x0, double z0, double x1, double z1) {
        int r = size() / 2;
        for (long key : FrontlineMath.chunksAlong(x0, z0, x1, z1)) {
            int cx = ZoneGeometry.x(key), cz = ZoneGeometry.z(key);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (STROKE.size() >= PacketZoneEdit.MAX_CHUNKS) return;
                    STROKE.add(ZoneGeometry.pack(cx + dx, cz + dz));
                }
            }
        }
    }

    /** Ends the stroke and sends it. */
    public static void commit() {
        if (!stroking) return;
        stroking = false;
        if (STROKE.isEmpty() || !armed()) {
            STROKE.clear();
            return;
        }
        byte type = switch (brush) {
            case PRIORITY -> PacketZoneEdit.SET_PRIORITY;
            case NOZONE -> PacketZoneEdit.SET_NOZONE;
            default -> PacketZoneEdit.ERASE;
        };
        NetworkHandler.CHANNEL.sendToServer(new PacketZoneEdit(type, STROKE.toLongArray()));
        if (STROKE.size() >= PacketZoneEdit.MAX_CHUNKS && Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(
                    Component.translatable("gui.tacz_sewv.zones.draw.capped", PacketZoneEdit.MAX_CHUNKS), true);
        }
        STROKE.clear();
    }

    public static void cancelStroke() {
        stroking = false;
        STROKE.clear();
    }

    public static void reset() {
        brush = Brush.OFF;
        cancelStroke();
    }
}
