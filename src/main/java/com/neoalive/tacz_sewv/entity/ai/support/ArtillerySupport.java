package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.ArtilleryEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils;
import com.atsuishio.superbwarfare.tools.TrajectoryCalculator;
import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.joml.Vector3f;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.bridge.FireMission;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;

/**
 * Aim for AI-crewed SPH / coastal artillery ({@link ArtilleryEntity} on the artillery clue list,
 * e.g. PLZ-05).
 *
 * <p>Solutions come from {@link BallisticArc} with the gunner's weapon velocity/gravity and the
 * turret's pitch stops. This class writes nothing to the hull: {@code MixinArtilleryShootVec}
 * puts {@link #layVector} into {@code shootVec} (synced), and the artillery branch of
 * {@code MixinVehicleMissileAim} slews the turret onto it — one writer each, gated on
 * {@link #isLaying}. {@code ManArtilleryGoal} only decides to fire, off the real barrel.
 */
public final class ArtillerySupport {

    private static final String WEAPON = "Main";
    private static final Logger LOGGER = LogUtils.getLogger();

    private ArtillerySupport() {}

    public static boolean isCrewing(AbstractUnit unit) {
        if (!(unit.getVehicle() instanceof VehicleEntity hull)) return false;
        if (!HullFacts.isArtilleryHull(hull)) return false;
        if (!(hull instanceof ArtilleryEntity)) return false;
        int seat = hull.getSeatIndex(unit);
        return seat >= 0 && (seat == 0 || seat == hull.getTurretControllerIndex());
    }

    public static boolean isArtilleryHull(VehicleEntity hull) {
        return HullFacts.isArtilleryHull(hull) && hull instanceof ArtilleryEntity;
    }

    /** True when the crew has a designation worth firing on. */
    public static boolean hasFireWork(AbstractUnit unit) {
        return aimpoint(unit) != null;
    }

    @Nullable
    public static FireMission fireMissionOf(AbstractUnit unit) {
        if (!(unit instanceof IMortarCrew crew)) return null;
        FireMission mission = crew.sewv$getFireMission();
        if (mission == null) return null;
        if (mission.isExpired(unit.level().getGameTime())) {
            crew.sewv$setFireMission(null);
            return null;
        }
        return mission;
    }

    /**
     * What the crew lays on: a designated live target, else the standing grid mission, else null.
     *
     * <p>A target the crew picked up by itself (SEM's own scans) does NOT count — it would pull the
     * gun off a grid mission onto whatever wandered into the crew's 96-block eyesight. Only a
     * {@link FireMissionSupport} designation does. A PMC under any order but FREE_FIRE /
     * ATTACK_THAT_TARGET lays nothing at all, which is what makes a MOVE order stop the laying
     * (the mission stays stored and expires on its own deadline).
     */
    @Nullable
    public static Vec3 aimpoint(AbstractUnit unit) {
        if (unit instanceof PmcUnitEntity pmc
                && pmc.getOrder() != OrderType.FREE_FIRE && pmc.getOrder() != OrderType.ATTACK_THAT_TARGET) {
            return null;
        }
        LivingEntity target = designatedTarget(unit);
        if (target != null) {
            return target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
        }
        FireMission mission = fireMissionOf(unit);
        return mission == null ? null : Vec3.atCenterOf(mission.pos());
    }

    /**
     * RU/US crew → network id of the entity {@code FireMissionSupport.callFireMission} last handed
     * it. Transient on purpose (a network id is not stable across sessions); weak so a dead or
     * unloaded crew drops out. PMC needs no entry: its designation IS the ATTACK_THAT_TARGET order.
     */
    private static final Map<AbstractUnit, Integer> DESIGNATED = new WeakHashMap<>();

    /** Records that {@code target} was handed to an RU/US crew by a fire-mission call. */
    public static void markDesignated(AbstractUnit crew, LivingEntity target) {
        DESIGNATED.put(crew, target.getId());
    }

    @Nullable
    private static LivingEntity designatedTarget(AbstractUnit unit) {
        LivingEntity t = unit.getTarget();
        if (t == null || !t.isAlive()) return null;
        if (unit instanceof PmcUnitEntity pmc) {
            return pmc.getOrder() == OrderType.ATTACK_THAT_TARGET && pmc.getAttackTargetId() == t.getId() ? t : null;
        }
        Integer id = DESIGNATED.get(unit);
        return id != null && id == t.getId() ? t : null;
    }

    /**
     * The one "is this gun being laid?" predicate. {@code MixinArtilleryShootVec}, the artillery
     * branch of {@code MixinVehicleMissileAim} and {@code IdleCrewGoal} all read it, so exactly one
     * of them owns the turret on any tick. Server-side meaning only (it reads targets and
     * persistent data the client does not have).
     */
    public static boolean isLaying(VehicleEntity hull) {
        return hull instanceof ArtilleryEntity && !hull.isWreck() && HullFacts.isArtilleryHull(hull)
                && hull.getNthEntity(hull.getTurretControllerIndex()) instanceof AbstractUnit gunner
                && hasFireWork(gunner);
    }

    /** The gunner's selected weapon (TOS-1A's is "TOS", not "Main"). */
    public static String weaponOf(VehicleEntity hull, LivingEntity gunner) {
        int seat = hull.getSeatIndex(gunner);
        String name = seat < 0 ? null : hull.getGunName(seat);
        return name == null ? WEAPON : name;
    }

    /** Muzzle position for {@code weapon}, falling back to the hull's eye. */
    private static Vec3 muzzle(ArtilleryEntity hull, String weapon) {
        Vec3 from = hull.getShootPos(weapon, 1.0F);
        return from == null ? hull.getEyePosition() : from;
    }

    /**
     * SBW's own artillery aim drop ({@code ArtilleryEntity.setTarget}): the aimpoint is lowered by
     * {@code 0.5 + 0.0015 × distance}. Applied to every lay, reduced charge or not.
     */
    private static Vec3 dropAim(Vec3 from, Vec3 aim) {
        return aim.add(0.0, -0.5 - 0.0015 * from.distanceTo(aim), 0.0);
    }

    /** A lay: launch direction plus the muzzle speed to fire it at ({@code NaN} = full charge). */
    private record Lay(Vec3 dir, float velocity) {}

    @Nullable
    private static Lay solveLay(ArtilleryEntity hull, LivingEntity gunner, Vec3 aim) {
        String weapon = weaponOf(hull, gunner);
        Vec3 from = muzzle(hull, weapon);
        Vec3 to = dropAim(from, aim);
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double v = hull.getProjectileVelocity(weapon), g = hull.getProjectileGravity(weapon);
        double min = hull.getTurretMinPitch(), max = hull.getTurretMaxPitch();
        if (SewvConfig.ARTILLERY_REDUCED_CHARGE.get()) {
            ReducedCharge.Charge c = ReducedCharge.solve(dx, dy, dz, g,
                    SewvConfig.ARTILLERY_ARC_ELEVATION_DEG.get(), min, max, v);
            if (c != null) return new Lay(new Vec3(c.x(), c.y(), c.z()), (float) c.velocity());
            // No lob reaches it (above the apex, or past full-charge reach): fire the full-charge
            // solution instead — a shot that lands beats holding fire.
        }
        BallisticArc.Aim a = BallisticArc.solve(dx, dy, dz, v, g, min, max);
        return a == null ? null : new Lay(new Vec3(a.x(), a.y(), a.z()), Float.NaN);
    }

    /**
     * The launch direction that lands on {@code aim} within the turret's stops — the reduced
     * charge's when {@code artilleryReducedCharge} is on and one exists, else {@link BallisticArc}'s
     * full-charge one (high root first) — or null. Pure read: writes nothing to the hull.
     */
    @Nullable
    public static Vec3 solve(ArtilleryEntity hull, LivingEntity gunner, Vec3 aim) {
        try {
            Lay lay = solveLay(hull, gunner, aim);
            return lay == null ? null : lay.dir();
        } catch (Throwable t) {
            noteError(t);
            return null;
        }
    }

    /**
     * Hull → reduced-charge muzzle speed of THIS tick's lay. Written only by {@link #layVector}
     * (every server tick, from {@code ArtilleryEntity.baseTick}), cleared whenever the lay is not a
     * reduced charge; read only by {@code MixinArtilleryReducedCharge} as the shell spawns.
     */
    private static final Map<VehicleEntity, Float> CHARGE = new WeakHashMap<>();

    /** The reduced-charge speed to fire this hull's shell at, or null for the normal charge. */
    @Nullable
    public static Float chargeFor(VehicleEntity hull) {
        return CHARGE.get(hull);
    }

    /**
     * What {@code shootVec} should hold this tick, server-side, for an AI-crewed artillery hull.
     * Laying: the solution, or — when none fits the stops — the aimpoint's bearing at
     * {@link BallisticArc#fallbackElevDeg}. Not laying: the barrel's own direction, so the
     * client (which follows the synced {@code shootVec}) mirrors whatever the server's turret is
     * doing under SBW's aim or the idle sweep.
     */
    public static Vec3 layVector(ArtilleryEntity hull, AbstractUnit gunner) {
        CHARGE.remove(hull);
        Vec3 barrel = hull.getBarrelVector(1.0F);
        if (!isLaying(hull)) return barrel;
        Vec3 aim = aimpoint(gunner);
        if (aim == null) return barrel;
        try {
            Lay lay = solveLay(hull, gunner, aim);
            if (lay != null) {
                if (!Float.isNaN(lay.velocity())) CHARGE.put(hull, lay.velocity());
                return lay.dir();
            }
            String weapon = weaponOf(hull, gunner);
            Vec3 from = muzzle(hull, weapon);
            Vec3 to = dropAim(from, aim);
            double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
            double h = Math.sqrt(dx * dx + dz * dz);
            if (h < 1.0e-6) return barrel;
            double e = Math.toRadians(BallisticArc.fallbackElevDeg(dx, dy, dz,
                    hull.getProjectileVelocity(weapon), hull.getProjectileGravity(weapon),
                    hull.getTurretMinPitch(), hull.getTurretMaxPitch()));
            return new Vec3(dx / h * Math.cos(e), Math.sin(e), dz / h * Math.cos(e));
        } catch (Throwable t) {
            noteError(t);
            return barrel;
        }
    }

    private static void noteError(Throwable t) {
        if (!debug) return;
        lastSolveError = t.getClass().getSimpleName() + ":" + t.getMessage();
        if (!solveStackLogged) {
            solveStackLogged = true;
            LOGGER.warn("[sewv-artillery] solve threw (logged once)", t);
        }
    }

    /** Last exception swallowed by {@link #solve}/{@link #layVector}, for the debug line only. */
    private static volatile String lastSolveError = "-";
    private static volatile boolean solveStackLogged;

    /** Toggled by {@code /sewv debug artillery}: one line per crew tick while a crew has fire work. */
    public static volatile boolean debug;

    public static boolean toggleDebug() {
        debug = !debug;
        return debug;
    }

    /**
     * {@code [sewv-artillery]} trace: solved launch, the synced {@code shootVec}, the real barrel and
     * the hull, all as yaw/elevation degrees (elevation up-positive), plus the fire gate's verdict.
     */
    public static void debugLine(ArtilleryEntity hull, AbstractUnit unit, Vec3 aim,
                                 @Nullable Vec3 launch, String fire) {
        if (!debug) return;
        try {
            Vector3f sv = hull.getShootVec();
            Vec3 barrel = hull.getBarrelVector(1.0F);
            String err = launch == null ? "-" : String.format("%.2f", angleDeg(barrel, launch));
            long now = unit.level().getGameTime();
            Long swept = com.neoalive.tacz_sewv.entity.ai.goal.IdleCrewGoal.DEBUG_LAST_SWEEP.get(hull.getId());
            // Goals tick in selector order, so a sweep on the previous tick still counts as "running".
            boolean idleGoal = swept != null && swept >= now - 1;
            LOGGER.info("[sewv-artillery] t={} hull=#{} seat={} pos={},{},{} aim={},{},{} {} sol={} sv={} barrel={} hullRot={}/{} err={} charge={} fire={} idleGoal={} ex={}",
                    now, hull.getId(), hull.getSeatIndex(unit),
                    Math.round(hull.getX()), Math.round(hull.getY()), Math.round(hull.getZ()),
                    Math.round(aim.x), Math.round(aim.y), Math.round(aim.z),
                    ballistics(hull, weaponOf(hull, unit), aim),
                    launch == null ? "none" : yawElev(launch),
                    sv == null ? "null" : yawElev(new Vec3(sv.x, sv.y, sv.z)),
                    yawElev(barrel),
                    String.format("%.1f", hull.getYRot()), String.format("%.1f", -hull.getXRot()),
                    err, chargeFor(hull) == null ? "full" : String.format("%.2f", chargeFor(hull)),
                    fire, idleGoal, lastSolveError);
            lastSolveError = "-";
        } catch (Throwable ignored) {
            // Debug output must never break the crew tick.
        }
    }

    /**
     * {@code dist=… v=… g=… env=min..max roots=e1/e2}: the raw inputs the solver sees and the
     * elevation (deg, up-positive) of every root SBW returns, before any envelope filter.
     * Each piece degrades to {@code ?} on its own so one bad read does not hide the rest.
     */
    private static String ballistics(ArtilleryEntity hull, String weapon, Vec3 aim) {
        String dist = "?", v = "?", g = "?", env = "?", roots = "?";
        Vec3 from = null;
        try {
            from = hull.getShootPos(weapon, 1.0F);
            if (from != null) dist = String.format("%.0f", from.distanceTo(aim));
        } catch (Throwable t) { dist = "?" + t.getClass().getSimpleName(); }
        double vel = Double.NaN, grav = Double.NaN;
        try { vel = hull.getProjectileVelocity(weapon); v = String.format("%.3f", vel); }
        catch (Throwable t) { v = "?" + t.getClass().getSimpleName(); }
        try { grav = hull.getProjectileGravity(weapon); g = String.format("%.4f", grav); }
        catch (Throwable t) { g = "?" + t.getClass().getSimpleName(); }
        try { env = hull.getTurretMinPitch() + ".." + hull.getTurretMaxPitch(); }
        catch (Throwable t) { env = "?" + t.getClass().getSimpleName(); }
        try {
            if (from != null && Double.isFinite(vel) && Double.isFinite(grav)) {
                StringBuilder sb = new StringBuilder();
                for (Vec3 r : TrajectoryCalculator.INSTANCE.calculateShootVectors(from, aim, vel, grav)) {
                    if (sb.length() > 0) sb.append('/');
                    sb.append(String.format("%.1f", VehicleVecUtils.getXRotFromVector(r)));
                }
                roots = sb.length() == 0 ? "none" : sb.toString();
            }
        } catch (Throwable t) { roots = "?" + t.getClass().getSimpleName(); }
        return "dist=" + dist + " v=" + v + " g=" + g + " env=" + env + " roots=" + roots;
    }

    private static String yawElev(Vec3 v) {
        return String.format("%.1f/%.1f",
                -VehicleVecUtils.getYRotFromVector(v), VehicleVecUtils.getXRotFromVector(v));
    }

    public static double angleDeg(Vec3 a, Vec3 b) {
        if (a.lengthSqr() < 1.0e-8 || b.lengthSqr() < 1.0e-8) return 180.0;
        double dot = Math.max(-1.0, Math.min(1.0, a.normalize().dot(b.normalize())));
        return Math.toDegrees(Math.acos(dot));
    }
}
