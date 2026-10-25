package com.neoalive.tacz_sewv.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.map.VehicleMarker;

/**
 * Where to draw a marker this frame. The sync is once a second, which a fullscreen map hides but a
 * minimap scrolling under a moving player does not — a plane jumps twenty blocks per packet. So when
 * the client already tracks the marked entity, its own interpolated position and yaw win, and the
 * synced snapshot is only the fallback for units beyond tracking range.
 *
 * <p>The wire carries the server entity id, which is the client network id (the order packets rely
 * on the same fact), and the synced yaw is that same entity's {@code getYRot()}, so the live values
 * describe exactly what the snapshot described. Nothing here leaks past fog of war: an entity the
 * client tracks is one Xaero's own radar would show anyway.
 */
public final class MarkerMotion {

    private MarkerMotion() {}

    public static double x(VehicleMarker marker, float partialTicks) {
        Entity e = live(marker);
        return e == null ? marker.x() : Mth.lerp(partialTicks, e.xo, e.getX());
    }

    public static double y(VehicleMarker marker, float partialTicks) {
        Entity e = live(marker);
        return e == null ? marker.y() : Mth.lerp(partialTicks, e.yo, e.getY());
    }

    public static double z(VehicleMarker marker, float partialTicks) {
        Entity e = live(marker);
        return e == null ? marker.z() : Mth.lerp(partialTicks, e.zo, e.getZ());
    }

    public static float yaw(VehicleMarker marker, float partialTicks) {
        Entity e = live(marker);
        return e == null ? marker.yaw() : Mth.rotLerp(partialTicks, e.yRotO, e.getYRot());
    }

    /** The tracked entity behind a marker, or null to fall back to the synced snapshot. */
    @Nullable
    private static Entity live(VehicleMarker marker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(marker.dimension())) return null;
        Entity e = mc.level.getEntity(marker.vehicleId());
        return e == null || e.isRemoved() ? null : e;
    }
}
