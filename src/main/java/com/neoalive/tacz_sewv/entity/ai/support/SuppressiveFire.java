package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;

/**
 * Keep an enemy's head down where it was last seen: Steve's Army's suppressive fire on a last-known
 * position, for a crew machine gun.
 *
 * <p>The moment a target breaks line of sight, SEM clears it and the crew used to fall silent. For a
 * few seconds afterwards ({@link #WINDOW_TICKS}) a crew with a machine gun may instead rake the spot
 * the enemy vanished from — the {@code SUPPRESS} plan in the utility brain. The rounds land through
 * SBW's ordinary bullets, so the infantry around that spot are suppressed by {@code SuppressionBus}
 * exactly as by any other fire.
 *
 * <p><b>One gun per contact.</b> Like SA's claim/heartbeat model, a contact (an 8-block cell, per
 * side) is claimed by one hull and released when its heartbeat lapses, so a platoon that loses one
 * enemy does not empty every belt into the same bush.
 *
 * <p><b>Fire discipline is ours to enforce here.</b> With no target, the canShoot gate skips its
 * line-of-fire and allied checks (they need a target), so this checks allies along the line and at
 * the point itself, and fires only through {@code canShoot} (the {@code TowEntity.vehicleShoot} rule).
 */
public final class SuppressiveFire {

    /** How long after losing sight the spot is still worth raking. */
    public static final long WINDOW_TICKS = 120;
    /** A claim not renewed for this long is free for someone else. */
    static final long HEARTBEAT_TICKS = 20;
    /** Claims are per cell this size, so two crews on the same lost enemy share one. */
    static final int CELL = 8;
    /** Beaten-zone half-width, as an angle at the gun. */
    static final double SPREAD_DEG = 1.5;
    static final double MIN_SPREAD = 0.75;
    static final double MAX_SPREAD = 3.0;
    /** SBW's own AI alignment tolerance before it lets a gun fire. */
    static final double ALIGN_DEG = 4.0;

    private record Claim(int hullId, long heartbeat) {}

    private static final Map<String, Claim> CLAIMS = new HashMap<>();
    private static long nextSweep = Long.MIN_VALUE;

    private SuppressiveFire() {}

    public static boolean enabled() {
        try {
            return SewvConfig.SUPPRESSIVE_FIRE_ENABLED.get();
        } catch (IllegalStateException unbaked) {
            return false;
        }
    }

    // --- planning (Facts / feasible) ---

    /**
     * The seat that would do the raking: a unit-crewed seat with a machine-gun slot, or -1. Read on the
     * utility refresh, and only while a lost contact is fresh, because the slot lookup reads gun data.
     */
    public static int machineGunSeat(VehicleEntity hull) {
        for (Entity p : hull.getPassengers()) {
            if (!(p instanceof AbstractUnit)) continue;
            int seat = hull.getSeatIndex(p);
            if (seat >= 0 && VehicleWeapons.slotForRole(hull, seat, VehicleWeapons.WEAPON_MG) >= 0) return seat;
        }
        return -1;
    }

    /** True if {@code hull} could hold the claim on {@code spot} right now. */
    public static boolean claimable(AbstractUnit crew, VehicleEntity hull, BlockPos spot, long now) {
        Claim c = CLAIMS.get(key(crew, spot));
        return c == null || c.hullId == hull.getId() || now - c.heartbeat > HEARTBEAT_TICKS;
    }

    /** True while {@code hull} is actively raking a spot — the turret is ours, not the idle sweep's. */
    public static boolean isSuppressing(VehicleEntity hull, long now) {
        for (Claim c : CLAIMS.values()) {
            if (c.hullId == hull.getId() && now - c.heartbeat <= HEARTBEAT_TICKS) return true;
        }
        return false;
    }

    // --- execution (DriveVehicleGoal's SUPPRESS dispatch) ---

    /**
     * One tick of raking {@code spot} from {@code seat}. Takes or renews the claim; movement is the caller's.
     * False when this hull cannot rake it (claim lost, gunner gone, no machine gun), so the caller can fall back.
     */
    public static boolean tick(AbstractUnit crew, VehicleEntity hull, int seat, BlockPos spot, long now) {
        if (seat < 0 || !(hull.getNthEntity(seat) instanceof AbstractUnit gunner)) return false;
        if (!claimable(crew, hull, spot, now)) return false;
        int mg = VehicleWeapons.slotForRole(hull, seat, VehicleWeapons.WEAPON_MG);
        if (mg < 0) return false;
        CLAIMS.put(key(crew, spot), new Claim(hull.getId(), now));
        sweep(now);

        if (hull.getWeaponIndex(seat) != mg) hull.setWeaponIndex(seat, mg);

        Vec3 from = hull.getShootPos(gunner, 1.0f);
        Vec3 center = Vec3.atCenterOf(spot).add(0.0, 0.5, 0.0);
        Vec3 aim = beatenZone(from, center, gunner.getId(), now);
        Vec3 dir = aim.subtract(from);
        if (dir.lengthSqr() < 1.0e-4) return true;

        if (seat == hull.getTurretControllerIndex() && hull.hasTurret()) {
            hull.turretAutoAimFromVector(dir.normalize());
        } else {
            gunner.lookAt(EntityAnchorArgument.Anchor.EYES, aim);
        }

        int rpm = Math.max(1, (int) Math.ceil(20.0 / (hull.vehicleWeaponRpm(gunner) / 60.0)));
        if (now % rpm != 0) return true;
        if (Math.toDegrees(Math.acos(clampCos(hull.getShootVec(gunner, 1.0f).normalize().dot(dir.normalize()))))
                > ALIGN_DEG) {
            return true;
        }
        if (VehicleTargeting.alliedInLineOfFire(gunner, hull, from, aim)) return true;
        if (VehicleTargeting.friendlyNearPoint(gunner, aim, SewvConfig.FRIENDLY_FIRE_VEHICLE_RADIUS.get())) return true;
        if (!hull.canShoot(gunner)) return true;
        hull.vehicleShoot(gunner, (UUID) null, aim);
        return true;
    }

    /**
     * A point inside the beaten zone around {@code center}, walking deterministically every half
     * second so the fire covers the spot rather than drilling one block.
     */
    static Vec3 beatenZone(Vec3 from, Vec3 center, int gunnerId, long now) {
        double r = spreadRadius(from.distanceTo(center));
        long step = now / 10;
        double a = hash01(gunnerId, step) * Math.PI * 2.0;
        double m = Math.sqrt(hash01(gunnerId * 31 + 7, step));
        return center.add(Math.cos(a) * r * m, (hash01(gunnerId * 17 + 3, step) - 0.5) * r * 0.5, Math.sin(a) * r * m);
    }

    /** Beaten-zone radius at {@code distance}: SA's {@code distance * tan(spread)}, clamped. */
    public static double spreadRadius(double distance) {
        double r = distance * Math.tan(Math.toRadians(SPREAD_DEG));
        return Math.max(MIN_SPREAD, Math.min(MAX_SPREAD, r));
    }

    static double hash01(long a, long b) {
        long h = (a * 0x9E3779B97F4A7C15L) ^ (b * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 31;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double clampCos(double c) {
        return Math.max(-1.0, Math.min(1.0, c));
    }

    private static String key(AbstractUnit crew, BlockPos spot) {
        CrewFacts.Faction faction = CrewFacts.factionOfCrew(crew);
        UUID owner = crew instanceof PmcUnitEntity pmc ? pmc.getOwnerUUID() : null;
        return crew.level().dimension().location() + "|" + faction + "|" + owner + "|"
                + Math.floorDiv(spot.getX(), CELL) + "," + Math.floorDiv(spot.getZ(), CELL);
    }

    private static void sweep(long now) {
        if (now < nextSweep) return;
        nextSweep = now + 200;
        Iterator<Claim> it = CLAIMS.values().iterator();
        while (it.hasNext()) {
            if (now - it.next().heartbeat > HEARTBEAT_TICKS) it.remove();
        }
    }

    /** Server stop / self-check reset. */
    public static void clearAll() {
        CLAIMS.clear();
        nextSweep = Long.MIN_VALUE;
    }
}
