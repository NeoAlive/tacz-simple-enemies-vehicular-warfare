package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.goal.DriveHelicopterGoal;
import com.neoalive.tacz_sewv.entity.ai.goal.DrivePlaneGoal;
import com.neoalive.tacz_sewv.network.PacketHelicopterCommand;

/**
 * Server-side flight-command writers shared by player packets and quick-command pipelines.
 * Reuses the same {@link IHelicopterPilot} + forced-land surface the TDT already drives —
 * do not invent a parallel flight state.
 */
public final class FlightOrders {

    private FlightOrders() {}

    /**
     * Emergency / field landing beside the aircraft. Returns {@code false} when no pad can be
     * found (caller should abort rather than leave a half-written command).
     */
    public static boolean emergencyLand(PmcUnitEntity pilot, VehicleEntity hull) {
        return emergencyLand(pilot, hull, false);
    }

    /**
     * @param randomPad when true, pick uniformly among dry pads in the search ring so multiple
     *                  helis do not all stack on the first spiral hit.
     */
    public static boolean emergencyLand(PmcUnitEntity pilot, VehicleEntity hull, boolean randomPad) {
        if (!(pilot.level() instanceof ServerLevel level)) return false;
        boolean plane = HullFacts.isPlaneHull(hull);
        BlockPos pad = randomPad ? emergencyPadRandom(level, hull, plane) : emergencyPad(level, hull, plane);
        if (pad == null) return false;
        return applyLand(pilot, hull, pad, plane);
    }

    /**
     * Land toward a focus (usually nearby infantry). Pads are searched around the focus, not the
     * hull — so an airborne heli flies to the troops instead of settling on a random field under
     * itself. {@code claimed} skips pads already taken by sibling helis this dispatch.
     */
    public static boolean landNear(PmcUnitEntity pilot, VehicleEntity hull, BlockPos focus,
                                   @Nullable Set<Long> claimed) {
        if (!(pilot.level() instanceof ServerLevel level)) return false;
        boolean plane = HullFacts.isPlaneHull(hull);
        BlockPos pad = padNear(level, focus, plane, claimed);
        if (pad == null) return false;
        if (claimed != null) claimed.add(pad.asLong());
        return applyLand(pilot, hull, pad, plane);
    }

    private static boolean applyLand(PmcUnitEntity pilot, VehicleEntity hull, BlockPos pad,
                                     boolean plane) {
        IHelicopterPilot heli = (IHelicopterPilot) pilot;
        heli.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_LANDING);
        heli.sewv$setHeliLandPos(pad);
        if (plane) {
            DrivePlaneGoal.setForcedLand(hull, pad);
        } else {
            DriveHelicopterGoal.setForcedLand(hull, pad);
        }
        return true;
    }

    /** Climb to (and hold) a cruise altitude. Clears any forced-land pad. */
    public static void takeoff(PmcUnitEntity pilot, VehicleEntity hull, int altitude) {
        IHelicopterPilot heli = (IHelicopterPilot) pilot;
        heli.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_TAKEOFF);
        heli.sewv$setHeliLandPos(null);
        heli.sewv$setCruiseAltitude(Mth.clamp(altitude,
                PacketHelicopterCommand.MIN_ALTITUDE, PacketHelicopterCommand.MAX_ALTITUDE));
        if (HullFacts.isPlaneHull(hull)) {
            DrivePlaneGoal.clearForcedLand(hull);
        } else {
            DriveHelicopterGoal.clearForcedLand(hull);
        }
    }

    /**
     * Same pad search as {@code PacketHelicopterCommand.emergencyPad} — kept here so quick-command
     * pipelines do not go through a second packet hop.
     */
    @Nullable
    public static BlockPos emergencyPad(ServerLevel level, VehicleEntity v, boolean plane) {
        List<BlockPos> pads = collectPads(level, v.getBlockX(), v.getBlockZ(), plane, v);
        return pads.isEmpty() ? null : pads.get(0);
    }

    @Nullable
    public static BlockPos emergencyPadRandom(ServerLevel level, VehicleEntity v, boolean plane) {
        List<BlockPos> pads = collectPads(level, v.getBlockX(), v.getBlockZ(), plane, v);
        if (pads.isEmpty()) return null;
        return pads.get(ThreadLocalRandom.current().nextInt(pads.size()));
    }

    @Nullable
    private static BlockPos padNear(ServerLevel level, BlockPos focus, boolean plane,
                                    @Nullable Set<Long> claimed) {
        List<BlockPos> pads = collectPads(level, focus.getX(), focus.getZ(), plane, null);
        for (BlockPos pad : pads) {
            if (claimed != null && claimed.contains(pad.asLong())) continue;
            return pad;
        }
        return null;
    }

    private static List<BlockPos> collectPads(ServerLevel level, int bx, int bz, boolean plane,
                                              @Nullable VehicleEntity planeHull) {
        List<BlockPos> out = new ArrayList<>();
        if (plane) {
            if (planeHull != null) {
                BlockPos pad = DrivePlaneGoal.findFieldPad(planeHull);
                if (pad != null) out.add(pad);
            }
            return out;
        }
        for (int r = 0; r <= 48; r += 4) {
            for (int dx = -r; dx <= r; dx += 4) {
                for (int dz = -r; dz <= r; dz += 4) {
                    if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    int x = bx + dx;
                    int z = bz + dz;
                    if (!level.hasChunkAt(x, z)) continue;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    if (y <= level.getMinBuildHeight()) continue;
                    BlockPos pad = new BlockPos(x, y, z);
                    if (level.getFluidState(pad).isEmpty()) out.add(pad);
                }
            }
        }
        return out;
    }
}
