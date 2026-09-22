package com.neoalive.tacz_sewv.airport;

import java.util.Set;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.init.ModBlocks;

/**
 * Snaps helicopters onto cleared helipads and answers whether a pad is taken.
 *
 * <p><b>A pad is occupied exactly when a live helicopter is snapped to it</b>, which is a tag on the
 * hull ({@link #TAG_PAD}, persistent data like {@code RunwayTraffic.TAG_SLOT}). Nothing else counts:
 * not a helicopter inbound, not one merely overhead.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class HelipadTraffic {

    public static final String TAG_PAD = "sewv:helipad";

    private static final int INTERVAL_TICKS = 10;
    /** An unmanned hull this close (blocks, beyond the 3x3) is pulled onto the pad. */
    private static final double UNMANNED_REACH = 2.0;
    /** A manned hull only snaps once landed, and only within one block of the 3x3. */
    private static final double MANNED_REACH = 1.0;
    /**
     * The decal is 0.5 px thick and sits on TOP of the pad block, at {@code pad.getY() + 1} — same
     * convention {@link com.neoalive.tacz_sewv.entity.ai.goal.DriveHelicopterGoal#touchdownY} uses
     * (it starts its open-column walk one block above the pad). Snapping to {@code pad.getY()}
     * instead put the hull inside the pad block and the ground under it, and physics shoved it
     * sideways trying to resolve that — the "pad pushes helicopters off" report.
     */
    private static final double DECAL_TOP = 1.0 + 1.0 / 32.0;
    /** How far above the pad layer a hull may be and still count as being at the pad. */
    private static final double BAND_ABOVE = 4.0;

    public enum Availability {
        NONE_IN_RANGE,
        ALL_OCCUPIED,
        FOUND
    }

    public record Pick(Availability availability, @Nullable BlockPos pad) {}

    private HelipadTraffic() {}

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        if (level.getGameTime() % INTERVAL_TICKS != 0) return;
        HelipadRegistry registry = HelipadRegistry.get(level);
        for (BlockPos pad : registry.pads()) {
            if (!level.isLoaded(pad)) continue;
            if (!level.getBlockState(pad).is(ModBlocks.HELIPAD.get())) {
                registry.forget(pad);
                continue;
            }
            snapPass(level, pad);
        }
    }

    private static void snapPass(ServerLevel level, BlockPos pad) {
        AABB foot = HelipadClearance.footprint(pad);
        AABB search = new AABB(foot.minX - UNMANNED_REACH, pad.getY() - 1, foot.minZ - UNMANNED_REACH,
                foot.maxX + UNMANNED_REACH, pad.getY() + BAND_ABOVE, foot.maxZ + UNMANNED_REACH);
        for (VehicleEntity v : level.getEntitiesOfClass(VehicleEntity.class, search,
                e -> e.isAlive() && HullFacts.isHelicopterHull(e))) {
            boolean unmanned = v.getPassengers().isEmpty();
            boolean tagged = isTagged(v, pad);
            double reach = unmanned ? UNMANNED_REACH : MANNED_REACH;
            boolean near = v.getX() >= foot.minX - reach && v.getX() <= foot.maxX + reach
                    && v.getZ() >= foot.minZ - reach && v.getZ() <= foot.maxZ + reach;

            // A manned hull that has left the ground, or anything that has left the pad, lets go.
            if (tagged && (!near || (!unmanned && !v.onGround()))) {
                v.getPersistentData().remove(TAG_PAD);
                continue;
            }
            if (tagged || !near) continue;
            if (!unmanned && !v.onGround()) continue;

            v.setDeltaMovement(Vec3.ZERO);
            v.moveTo(pad.getX() + 0.5, pad.getY() + DECAL_TOP, pad.getZ() + 0.5, v.getYRot(), v.getXRot());
            v.setOldPosAndRot();
            v.getPersistentData().putLong(TAG_PAD, pad.asLong());
        }
    }

    private static boolean isTagged(VehicleEntity v, BlockPos pad) {
        return v.getPersistentData().contains(TAG_PAD)
                && v.getPersistentData().getLong(TAG_PAD) == pad.asLong();
    }

    /** True when a live helicopter is snapped to this pad. */
    public static boolean isOccupied(ServerLevel level, BlockPos pad) {
        AABB foot = HelipadClearance.footprint(pad);
        AABB box = new AABB(foot.minX - UNMANNED_REACH, pad.getY() - 1, foot.minZ - UNMANNED_REACH,
                foot.maxX + UNMANNED_REACH, pad.getY() + BAND_ABOVE, foot.maxZ + UNMANNED_REACH);
        return !level.getEntitiesOfClass(VehicleEntity.class, box,
                e -> e.isAlive() && isTagged(e, pad)).isEmpty();
    }

    /**
     * The nearest cleared, unoccupied pad within {@code radius} of {@code from}, skipping any in
     * {@code claimed} (pads already given to another aircraft in the same order). Distinguishes "no
     * cleared pad in range" from "there are some, but all are taken" so the caller can say which.
     */
    public static Pick nearest(ServerLevel level, BlockPos from, double radius, Set<BlockPos> claimed) {
        if (radius <= 0.0) return new Pick(Availability.NONE_IN_RANGE, null);
        HelipadRegistry registry = HelipadRegistry.get(level);
        double r2 = radius * radius;
        boolean anyInRange = false;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pad : registry.pads()) {
            double d = pad.distSqr(from);
            if (d > r2) continue;
            if (level.isLoaded(pad) && !level.getBlockState(pad).is(ModBlocks.HELIPAD.get())) {
                registry.forget(pad);
                continue;
            }
            anyInRange = true;
            if (claimed.contains(pad) || isOccupied(level, pad)) continue;
            if (d < bestD) {
                bestD = d;
                best = pad;
            }
        }
        if (best != null) return new Pick(Availability.FOUND, best);
        return new Pick(anyInRange ? Availability.ALL_OCCUPIED : Availability.NONE_IN_RANGE, null);
    }
}
