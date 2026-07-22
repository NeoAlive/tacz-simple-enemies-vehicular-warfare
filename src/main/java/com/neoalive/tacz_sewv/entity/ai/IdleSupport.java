package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.jetbrains.annotations.Nullable;

/**
 * Where a crew with nothing to do goes: short drifts around the spot it went idle at, with pauses
 * where it sits completely still.
 *
 * <p>It produces nothing but a destination, handed to {@link VehicleTargeting#resolveDestination} as
 * the last fallback — so the driving itself is {@link DriveVehicleGoal}'s existing pathfinder,
 * steering and arrival handling, untouched. Reached only when a crew has no target, no ally to
 * reinforce and (for a PMC) no order but FREE_FIRE: a hull told to HOLD, MOVE, escort or hold a
 * formation slot still returns null and parks exactly where it was put.
 *
 * <p>State lives in the <b>hull's</b> {@code getPersistentData()} rather than a bridge interface on
 * the unit: it is a property of the vehicle's position, so a crew change inherits it, and it wants
 * to survive a save (unlike an entity-id order). All three timers are absolute game times.
 */
public final class IdleSupport {

    private IdleSupport() {}

    private static final String ANCHOR_KEY = "tacz_sewv:idle_anchor"; // where this hull went idle
    private static final String WAYPOINT_KEY = "tacz_sewv:idle_wp";   // absent = dwelling, sit still
    private static final String NEXT_KEY = "tacz_sewv:idle_next";     // game time of the next roll
    private static final String DRIVES_KEY = "tacz_sewv:idle_drives"; // 0 unknown, 1 yes, 2 no

    // One roll in this many is a dwell: the hull stops entirely rather than picking a new point.
    private static final int DWELL_IN = 3;
    private static final int DWELL_TICKS = 200;   // + up to DWELL_JITTER
    private static final int DWELL_JITTER = 400;
    private static final int LEG_TICKS = 300;     // how long a waypoint stands before being re-rolled
    private static final int LEG_JITTER = 300;

    /**
     * The idle destination for this crew, or null to stand still (dwelling, in contact, disabled, or
     * a hull that doesn't drive).
     */
    @Nullable
    public static BlockPos wanderPos(AbstractUnit unit, @Nullable VehicleEntity vehicle) {
        if (vehicle == null || !SewvConfig.IDLE_WANDER_ENABLED.get()) return null;

        CompoundTag data = vehicle.getPersistentData();
        // In contact: drop the whole idle state so that when the fight ends the hull re-anchors
        // where it actually finished, instead of driving back to where it started the day.
        if (unit.getTarget() != null) {
            data.remove(ANCHOR_KEY);
            data.remove(WAYPOINT_KEY);
            data.remove(NEXT_KEY);
            return null;
        }

        long now = vehicle.level().getGameTime();
        if (now < data.getLong(NEXT_KEY)) return waypoint(data); // holding a leg, or dwelling out a pause

        // computed() is expensive, so it is only reached on a roll (every 200+ ticks) and cached.
        if (!drives(vehicle, data)) return null;

        BlockPos anchor = data.contains(ANCHOR_KEY) ? BlockPos.of(data.getLong(ANCHOR_KEY)) : vehicle.blockPosition();
        data.putLong(ANCHOR_KEY, anchor.asLong());

        RandomSource random = unit.getRandom();
        if (random.nextInt(DWELL_IN) == 0) {
            data.remove(WAYPOINT_KEY);
            data.putLong(NEXT_KEY, now + DWELL_TICKS + random.nextInt(DWELL_JITTER));
            return null;
        }

        data.putLong(NEXT_KEY, now + LEG_TICKS + random.nextInt(LEG_JITTER));
        // Same rejection sample a patrol uses, at a much smaller radius: a point on loaded, dry,
        // drivable ground, i.e. exactly a node the driver's own pathfinder can route to.
        BlockPos next = PatrolSupport.pickWaypoint(
                vehicle.level(), anchor, SewvConfig.IDLE_WANDER_RADIUS.get(), random);
        if (next == null) {
            data.remove(WAYPOINT_KEY); // nothing valid nearby — dwell this leg out instead
            return null;
        }
        data.putLong(WAYPOINT_KEY, next.asLong());
        return next;
    }

    @Nullable
    private static BlockPos waypoint(CompoundTag data) {
        return data.contains(WAYPOINT_KEY) ? BlockPos.of(data.getLong(WAYPOINT_KEY)) : null;
    }

    /**
     * Whether this hull is something that drives on the ground at all. Helicopters and aircraft have
     * their own goal ({@link DriveHelicopterGoal}) and FIXED mounts — the TOW, the naval guns, the
     * mortar — have no drivetrain to idle with, so both simply stay put and keep only the turret
     * sweep and radio chatter of {@link IdleCrewGoal}. Ships fall out too: an idle waypoint is dry
     * WALKABLE ground by construction, which a ship could never reach.
     */
    private static boolean drives(VehicleEntity vehicle, CompoundTag data) {
        byte cached = data.getByte(DRIVES_KEY);
        if (cached != 0) return cached == 1;

        boolean drives;
        try {
            EngineType type = vehicle.computed().getEngineType();
            drives = type == EngineType.WHEEL || type == EngineType.TRACK;
        } catch (Throwable ignored) {
            drives = false; // unreadable hull data: parking is the safe answer
        }
        data.putByte(DRIVES_KEY, (byte) (drives ? 1 : 2));
        return drives;
    }
}
