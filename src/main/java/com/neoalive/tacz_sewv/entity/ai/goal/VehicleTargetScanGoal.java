package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.command.CrewAssignment;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.sensor.HullLocalScan;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;

/**
 * Target acquisition for mounted crews: a flat cylinder scan around the VEHICLE
 * (configurable radius and height) instead of the vanilla follow-range scan SEM
 * units come with. The vanilla box is capped by the unit's follow-range attribute
 * and only reaches ±4 blocks vertically — both hopeless at vehicle engagement
 * ranges, where the standoff ring alone sits at 40 blocks.
 *
 * The cylinder is the deliberate shape: full horizontal reach where ground
 * targets actually are, without paying to scan a 96-block-tall box of sky and
 * caves. Runs at priority 2 with the TARGET flag, so while a mounted crew holds
 * a target this goal owns targeting over SEM's short-range scans, but SEM's
 * HurtByTargetGoal (priority 1) still preempts for retaliation.
 */
public class VehicleTargetScanGoal extends Goal {

    // Acquired targets are only dropped past 1.5x the scan bounds, so a target
    // maneuvering along the cylinder's edge doesn't flicker in and out of lock.
    private static final double DROP_MULT = 1.5;

    /**
     * Soft focus-fire: treat the commander's priority target as this fraction of its real
     * distance so it sorts earlier — never a hard setTarget. A much closer other contact still wins.
     */
    private static final double FOCUS_DIST_SCALE = 0.35;

    // A lock whose target breaks line of sight is held through a short grace period
    // (aim stays on the corner it vanished behind) and then dropped, so the crew
    // rescans for someone it can actually shoot instead of staring at a wall.
    // Firing during the grace is separately suppressed in MixinVehicleFireCooldown,
    // so nothing leaks through the wall while the lock lingers.
    // 60 goal ticks ≈ 6 s wall clock: goals tick every other game tick.
    private static final int LOS_GRACE_TICKS = 60;

    private final AbstractUnit unit;
    private VehicleEntity vehicle;
    private LivingEntity pendingTarget;
    private int scanCooldown;
    private int ticksWithoutLos;

    public VehicleTargetScanGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (!(this.unit.getVehicle() instanceof VehicleEntity v) || v.isWreck()) return false;
        // Artillery waits for radio / fire-mission designation — never freelances a close fight.
        if (HullFacts.isArtilleryHull(v)) return false;
        if (!VehicleTargeting.ordersAllowAutoTargets(this.unit)) return false;

        LivingEntity current = this.unit.getTarget();
        if (current != null && current.isAlive()) return false; // already engaged — don't retarget every scan

        // Throttle: the AABB query + sort is the expensive part, so it only runs
        // every N ticks; the cheap mounted/target checks above still run per tick.
        if (this.scanCooldown > 0) {
            this.scanCooldown--;
            return false;
        }

        this.vehicle = v;

        // Driver owns the cylinder scan. Other seats copy the driver's lock (one AABB per hull)
        // and only fall back to a rare own scan when that lock fails seat LoS — see plan PR A.
        if (v.getFirstPassenger() != this.unit) {
            this.pendingTarget = copyDriverLockOrRareScan(v);
        } else {
            this.scanCooldown = SewvConfig.VEHICLE_TARGET_SCAN_INTERVAL_TICKS.get();
            this.pendingTarget = scanCylinder(v);
        }

        SewvDiag.scan(
                "VehicleTargetScanGoal.canUse unit={}#{} vehicle={}#{} pendingTarget={}#{} (null=no lock this scan)",
                this.unit.getClass().getSimpleName(), this.unit.getId(),
                v.getName().getString(), v.getId(),
                this.pendingTarget == null ? "null" : this.pendingTarget.getClass().getSimpleName(),
                this.pendingTarget == null ? -1 : this.pendingTarget.getId());
        return this.pendingTarget != null;
    }

    /**
     * Prefer the driver's live lock when this seat can see it; otherwise a 2×-interval own
     * cylinder scan so a gunner behind cover is not stuck silent forever.
     */
    @Nullable
    private LivingEntity copyDriverLockOrRareScan(VehicleEntity v) {
        if (v.getFirstPassenger() instanceof AbstractUnit driver) {
            LivingEntity lock = driver.getTarget();
            if (lock != null && lock.isAlive() && isValidTarget(v, lock)) {
                boolean needLos = SewvConfig.VEHICLE_TARGET_REQUIRE_LOS.get()
                        && !DriveHelicopterGoal.inFiringRun(v);
                if (!needLos || this.unit.getSensing().hasLineOfSight(lock)) {
                    this.scanCooldown = SewvConfig.VEHICLE_TARGET_SCAN_INTERVAL_TICKS.get();
                    return lock;
                }
            }
        }
        // No usable shared lock (absent, invalid, or occluded from this seat) — rare own scan.
        this.scanCooldown = SewvConfig.VEHICLE_TARGET_SCAN_INTERVAL_TICKS.get() * 2;
        return scanCylinder(v);
    }

    @Override
    public void start() {
        SewvDiag.scan("VehicleTargetScanGoal.start → setTarget unit={}#{} target={}#{}",
                this.unit.getClass().getSimpleName(), this.unit.getId(),
                this.pendingTarget.getClass().getSimpleName(), this.pendingTarget.getId());
        // SPOTTED is announced from setTarget itself (MixinUnitVoicelines), which catches every path
        // a vehicle lock arrives through -- this scan, the priority goal, or a player order.
        this.unit.setTarget(this.pendingTarget);
        LivingEntity after = this.unit.getTarget();
        SewvDiag.scan("VehicleTargetScanGoal.start AFTER setTarget getTarget={}#{} accepted={}",
                after == null ? "null" : after.getClass().getSimpleName(),
                after == null ? -1 : after.getId(),
                after == this.pendingTarget);
        this.pendingTarget = null;
        this.ticksWithoutLos = 0;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.vehicle == null || this.unit.getVehicle() != this.vehicle || this.vehicle.isWreck()) return false;
        if (!VehicleTargeting.ordersAllowAutoTargets(this.unit)) return false;

        LivingEntity target = this.unit.getTarget();
        if (target == null || !target.isAlive() || !isValidTarget(this.vehicle, target)) return false;

        // LOS is re-checked for the whole life of the lock, not just at acquisition —
        // otherwise a target stepping behind a wall stays locked forever. Sensing
        // caches the raycast per tick, so this costs one clip per crew per tick.
        // Suspended during an active heli firing run: pitch/bank occludes the pilot's
        // own ray without the target having left — dropping then would abort the pass.
        if (SewvConfig.VEHICLE_TARGET_REQUIRE_LOS.get()
                && !DriveHelicopterGoal.inFiringRun(this.vehicle)) {
            if (this.unit.getSensing().hasLineOfSight(target)) {
                this.ticksWithoutLos = 0;
            } else if (++this.ticksWithoutLos > LOS_GRACE_TICKS) {
                return false; // hidden too long — release the lock and rescan
            }
        } else {
            this.ticksWithoutLos = 0;
        }

        double dropRadius = SewvConfig.VEHICLE_TARGET_SCAN_RADIUS.get() * DROP_MULT;
        double dropHalfHeight = SewvConfig.VEHICLE_TARGET_SCAN_HEIGHT.get() / 2.0 * DROP_MULT;
        // A flying vehicle keeps its lock on targets all the way down to the ground —
        // without the slack, climbing to cruise altitude would drop the very target
        // the crew is engaging (the cylinder is centered on the hull).
        return horizontalDistSq(this.vehicle, target) <= dropRadius * dropRadius
                && target.getY() - this.vehicle.getY() <= dropHalfHeight
                && this.vehicle.getY() - target.getY() <= dropHalfHeight + altitudeSlack(this.vehicle);
    }

    @Override
    public void stop() {
        // Vanilla TargetGoal contract: releasing the TARGET flag clears the target,
        // so whichever target goal runs next (retaliation, order-driven) starts clean.
        this.unit.setTarget(null);
        this.vehicle = null;
        this.pendingTarget = null;
        this.ticksWithoutLos = 0;
    }

    // Nearest valid enemy inside the cylinder: AABB query for the bounding box,
    // then the horizontal-distance filter rounds the corners off into a cylinder.
    // LOS raycasts (when enabled) only run down the sorted list until the first
    // visible candidate, not against everything found.
    private LivingEntity scanCylinder(VehicleEntity v) {
        List<LivingEntity> candidates = collectCylinderCandidates(v, DriveHelicopterGoal.inFiringRun(v));
        SewvDiag.scan(
                "VehicleTargetScanGoal.scanCylinder unit={}#{} rawCandidates={} ids={}",
                this.unit.getClass().getSimpleName(), this.unit.getId(),
                candidates.size(),
                candidates.stream().map(e -> e.getClass().getSimpleName() + "#" + e.getId()).toList());
        // Nearest-first (with a soft bias toward the commander's focus id), then the first
        // candidate the crew can actually see (every candidate, when LOS is off). Raycasts only
        // run down the list until one passes.
        candidates.sort(Comparator.comparingDouble(e -> focusAdjustedDistSq(v, e)));
        // Mid firing-run reacquire must not demand LOS every scan interval — the same
        // pitch/bank that flickered the lock would block re-lock for the whole pass.
        boolean needLos = SewvConfig.VEHICLE_TARGET_REQUIRE_LOS.get()
                && !DriveHelicopterGoal.inFiringRun(v);
        for (LivingEntity candidate : candidates) {
            boolean los = !needLos || this.unit.getSensing().hasLineOfSight(candidate);
            if (los) {
                SewvDiag.scan("VehicleTargetScanGoal.scanCylinder PICK unit={}#{} candidate={}#{} needLos={}",
                        this.unit.getClass().getSimpleName(), this.unit.getId(),
                        candidate.getClass().getSimpleName(), candidate.getId(), needLos);
                return candidate;
            }
            SewvDiag.scan("VehicleTargetScanGoal.scanCylinder SKIP_LOS unit={}#{} candidate={}#{}",
                    this.unit.getClass().getSimpleName(), this.unit.getId(),
                    candidate.getClass().getSimpleName(), candidate.getId());
        }
        SewvDiag.scan("VehicleTargetScanGoal.scanCylinder NO_PICK unit={}#{} (empty or all failed LOS)",
                this.unit.getClass().getSimpleName(), this.unit.getId());
        return null;
    }

    /**
     * Debug probe at abandon time: is there still a valid enemy in the mounted scan
     * cylinder, and can the pilot see it? Separates false losses (inRange=true) from
     * genuine end-of-fight (inRange=false). Includes the min-range dead zone so an
     * overfly-close live target still counts as something we should have kept.
     */
    public record RelockProbe(boolean inRange, boolean hasLos, int id) {
        static final RelockProbe NONE = new RelockProbe(false, false, -1);
    }

    /** Nearest valid in-cylinder enemy for abandon diagnostics — never assigns a target. */
    public static RelockProbe probeRelock(AbstractUnit unit, VehicleEntity v) {
        LivingEntity best = findHandoffTarget(unit, v);
        if (best == null) return RelockProbe.NONE;
        boolean los = unit.getSensing().hasLineOfSight(best);
        return new RelockProbe(true, los, best.getId());
    }

    /**
     * Best handoff / relock candidate in the mounted cylinder. During a firing run
     * LOS is not required (same rule as mid-pass reacquire); otherwise respects
     * {@link SewvConfig#VEHICLE_TARGET_REQUIRE_LOS}. Never assigns the target.
     */
    @Nullable
    public static LivingEntity findHandoffTarget(AbstractUnit unit, VehicleEntity v) {
        if (unit == null || v == null) return null;
        try {
            VehicleTargetScanGoal probe = new VehicleTargetScanGoal(unit);
            probe.vehicle = v;
            boolean inRun = DriveHelicopterGoal.inFiringRun(v);
            List<LivingEntity> candidates = probe.collectCylinderCandidates(v, true);
            if (candidates.isEmpty()) return null;
            candidates.sort(Comparator.comparingDouble(e -> probe.focusAdjustedDistSq(v, e)));
            boolean needLos = SewvConfig.VEHICLE_TARGET_REQUIRE_LOS.get() && !inRun;
            for (LivingEntity candidate : candidates) {
                if (!needLos || unit.getSensing().hasLineOfSight(candidate)) {
                    return candidate;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * @param includeClose when true, keep candidates inside {@link VehicleMinRangeGoal}'s
     *                     dead zone (firing-run overfly / abandon probe). Acquisition while
     *                     idle still excludes them so MinRange doesn't thrash the lock.
     */
    private List<LivingEntity> collectCylinderCandidates(VehicleEntity v, boolean includeClose) {
        double radius = SewvConfig.VEHICLE_TARGET_SCAN_RADIUS.get();
        double radiusSq = radius * radius;
        // Shared per-hull LivingEntity fill — driver scan and Facts force counts reuse it.
        List<LivingEntity> raw = HullLocalScan.livingInScanCylinder(v);
        List<LivingEntity> out = new java.util.ArrayList<>(Math.min(raw.size(), 16));
        for (LivingEntity e : raw) {
            if (!isValidTarget(v, e)) continue;
            double distSq = horizontalDistSq(v, e);
            if (distSq > radiusSq) continue;
            if (!includeClose && distSq < VehicleMinRangeGoal.MIN_ENGAGE_DISTANCE_SQ) continue;
            out.add(e);
        }
        return out;
    }

    /** Distance used for ranking — shrinks the commander's priority target without locking it. */
    private double focusAdjustedDistSq(VehicleEntity v, LivingEntity e) {
        double d = horizontalDistSq(v, e);
        Integer focus = CrewAssignment.priorityTargetId(this.unit.getId());
        if (focus != null && e.getId() == focus) {
            return d * FOCUS_DIST_SCALE;
        }
        return d;
    }

    // Mirrors SEM's faction doctrine (from its vanilla target goals): RU/US fight
    // players, the opposing factions and hostile mobs (plus iron golems); PMC crews
    // fight RU/US and hostile mobs but never auto-target players or other PMCs.
    // AbstractUnit extends Monster, so the Enemy check covers opposing units too —
    // each branch just has to carve its own faction back out.
    private boolean isValidTarget(VehicleEntity v, LivingEntity e) {
        if (e == this.unit || !e.isAlive() || !e.isAttackable()) {
            return false;
        }
        if (e.getVehicle() == v) return false; // riding our own hull — crewmate or min-range hugger
        // Area task commitment: only lock contacts inside the ordered ground (rect / disk).
        // The scan cylinder reaches far past a sweep AABB; without this a distant zombie steals
        // the lock, quiet never settles, and Sweep & Advance never claims.
        if (this.unit instanceof PmcUnitEntity pmc
                && PatrolSupport.holdsCourseThroughContact(pmc)
                && !PatrolSupport.isInsideAreaTask(pmc, e)) {
            return false;
        }
        // Honour SEM's per-faction friendly flag before the explicit Player branch below would
        // otherwise lock any player — the reported "friendly US helicopter fires on the player"
        // bug. Excludes friendly players/PMC here (not just at setTarget) so the scan skips them
        // and moves on to the next candidate rather than spinning on one it can never lock.
        boolean nonHostile = VehicleTargeting.isNonHostile(this.unit, e);
        if (nonHostile) {
            // PMC↔PMC and PMC↔Player are the gaps under investigation (invasion enemy lists).
            if (this.unit instanceof PmcUnitEntity
                    && (e instanceof PmcUnitEntity || e instanceof Player)) {
                SewvDiag.scan(
                        "VehicleTargetScanGoal.isValidTarget REJECT isNonHostile=true unit={}#{} cand={}#{}",
                        this.unit.getClass().getSimpleName(), this.unit.getId(),
                        e.getClass().getSimpleName(), e.getId());
            }
            return false;
        }

        if (this.unit instanceof PmcUnitEntity) {
            // SEM doctrine: PMC never auto-targets other PMC — except Stage 4 ENEMY pairs,
            // which isNonHostile already admits and which must reach the candidate list.
            if (e instanceof PmcUnitEntity) {
                if (VehicleTargeting.isDiplomacyEnemy(this.unit, e)) {
                    SewvDiag.scan(
                            "VehicleTargetScanGoal.isValidTarget ALLOW diplomacyEnemy Pmc "
                                    + "unit={}#{} cand={}#{} isNonHostile=false instanceofEnemy={}",
                            this.unit.getClass().getSimpleName(), this.unit.getId(),
                            e.getClass().getSimpleName(), e.getId(),
                            e instanceof Enemy);
                    return VehicleTargeting.categoryAllowed(this.unit, e);
                }
                SewvDiag.scan(
                        "VehicleTargetScanGoal.isValidTarget REJECT hardPmcExclusion "
                                + "unit={}#{} cand={}#{} isNonHostile=false instanceofEnemy={} "
                                + "diplomacyEnemy=false → DROP (ALLY/NEUTRAL/unresolved)",
                        this.unit.getClass().getSimpleName(), this.unit.getId(),
                        e.getClass().getSimpleName(), e.getId(),
                        e instanceof Enemy);
                return false;
            }
            return VehicleTargeting.categoryAllowed(this.unit, e);
        }
        if (e instanceof Player p) return !p.isCreative() && !p.isSpectator();
        if (e instanceof IronGolem) return true;
        if (this.unit instanceof RUunitEntity) {
            return VehicleTargeting.categoryAllowed(this.unit, e) && !(e instanceof RUunitEntity);
        }
        if (this.unit instanceof USunitEntity) {
            return VehicleTargeting.categoryAllowed(this.unit, e) && !(e instanceof USunitEntity);
        }
        return false;
    }

    // Extra downward reach for flying vehicles: their height above the terrain
    // surface. Zero for ground vehicles (hull sits on the surface) and for a heli
    // parked on the ground, growing exactly as fast as the aircraft climbs, so the
    // cylinder's bottom face stays pinned to the ground where the targets are.
    private static double altitudeSlack(VehicleEntity v) {
        if (!HullFacts.isHelicopterHull(v) && !HullFacts.isPlaneHull(v)) return 0.0;
        int surface = v.level().getHeight(Heightmap.Types.WORLD_SURFACE, v.getBlockX(), v.getBlockZ());
        return Math.max(0.0, v.getY() - surface);
    }

    private static double horizontalDistSq(VehicleEntity v, LivingEntity e) {
        double dx = e.getX() - v.getX();
        double dz = e.getZ() - v.getZ();
        return dx * dx + dz * dz;
    }
}
