package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.compat.SoftEnemyTargeting;
import com.neoalive.tacz_sewv.compat.SoftEnemyTargeting.Tier;
import com.neoalive.tacz_sewv.compat.TripodCompat;
import com.neoalive.tacz_sewv.config.EasyMode;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Target-priority nudge for third-party enemy mods a unit's normal ladder can't tell apart
 * from a plain vanilla monster by class alone — see {@link SoftEnemyTargeting} for the tier
 * rules (Spore's {@code organoid}/{@code experiments} outrank its {@code infected}, which ties
 * with any Phayriosis Two mob, both above a bare {@code MONSTER}).
 *
 * <p>Sits at priority 1 for the exact reason {@link CrewTargetPriorityGoal} does (see that
 * class's doc): SEM's own ladder puts its catch-all {@code Monster} scan at the SAME priority
 * as its troop scans, and vanilla's {@code WrappedGoal.canBeReplacedBy} only lets a STRICTLY
 * higher priority steal a held flag — so nothing at 2+ can ever pull a unit off a zombie onto a
 * higher-tier target once that catch-all locks it. This ties with {@code CrewTargetPriorityGoal}
 * and retaliation at 1, the same deliberate draw documented there.
 *
 * <p>Unlike {@code CrewTargetPriorityGoal} this carries no weapon doctrine — it runs on every
 * unit, mounted or not — so its very first gate is {@link SoftEnemyTargeting#anyPresent()}: with
 * neither mod installed this returns false before touching the world at all, exactly the
 * "implemented only if present" softcompat this is.
 *
 * <p>A second instance with {@code minTier = APEX} sits at priority 0 when Tripod Invasion is
 * present ({@link TripodCompat}): strictly above retaliation and every priority-1 pick, so a tripod
 * is the top target for PMC/RU/US. It ties only with SEM's ATTACK_THAT_TARGET goal, which runs
 * exactly when {@link VehicleTargeting#ordersAllowAutoTargets} switches this one off. Its candidates
 * are the tripod list, never a world scan.
 */
public class SoftEnemyTargetPriorityGoal extends Goal {

    /** Acquired targets are held past the scan edge so an edge-walker doesn't flicker. */
    private static final double DROP_MULT = 1.5;

    private final AbstractUnit unit;
    /** Lowest tier this instance picks or holds; NONE = the original priority-1 behaviour. */
    private final Tier minTier;
    private LivingEntity pendingTarget;
    private int scanCooldown;

    public SoftEnemyTargetPriorityGoal(AbstractUnit unit) {
        this(unit, Tier.NONE);
    }

    public SoftEnemyTargetPriorityGoal(AbstractUnit unit, Tier minTier) {
        this.unit = unit;
        this.minTier = minTier;
        this.setFlags(EnumSet.of(Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (this.minTier == Tier.APEX ? !TripodCompat.present() : !SoftEnemyTargeting.anyPresent()) return false;
        if (!VehicleTargeting.ordersAllowAutoTargets(this.unit)) return false;

        LivingEntity current = this.unit.getTarget();
        Tier currentTier = current != null && current.isAlive()
                ? SoftEnemyTargeting.classify(current)
                : Tier.NONE;
        // Already on the top tier — nothing can outrank it, so don't pay for a scan.
        if (currentTier == Tier.APEX || (this.minTier != Tier.APEX && currentTier == Tier.PRIME)) return false;

        if (this.scanCooldown > 0) {
            this.scanCooldown--;
            return false;
        }
        this.scanCooldown = EasyMode.scanIntervalTicks(this.unit, SewvConfig.VEHICLE_TARGET_SCAN_INTERVAL_TICKS.get());

        this.pendingTarget = scan(currentTier);
        return this.pendingTarget != null;
    }

    @Override
    public void start() {
        this.unit.setTarget(this.pendingTarget);
        this.pendingTarget = null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!VehicleTargeting.ordersAllowAutoTargets(this.unit)) return false;

        LivingEntity target = this.unit.getTarget();
        if (target == null || !target.isAlive()) return false;
        // The crew moved on to something else (retaliation, an order) — let it go rather than
        // drag it back; this goal's job is the pick, not custody.
        Tier tier = SoftEnemyTargeting.classify(target);
        if (tier == Tier.NONE || tier.ordinal() < this.minTier.ordinal()) return false;

        double drop = scanRadius() * DROP_MULT;
        return this.unit.distanceToSqr(target) <= drop * drop;
    }

    @Override
    public void stop() {
        // Vanilla TargetGoal contract: releasing TARGET clears the target, so whichever goal
        // picks it up next (retaliation, the ladder's own scans) starts clean.
        this.unit.setTarget(null);
        this.pendingTarget = null;
    }

    /** Nearest highest-tier candidate strictly above {@code currentTier}, or null. */
    private LivingEntity scan(Tier currentTier) {
        double radius = scanRadius();
        double radiusSq = radius * radius;

        Predicate<LivingEntity> wanted = e -> SoftEnemyTargeting.classify(e).ordinal() > currentTier.ordinal()
                && SoftEnemyTargeting.classify(e).ordinal() >= this.minTier.ordinal()
                && VehicleTargeting.isValidHostileTarget(this.unit, e)
                && this.unit.distanceToSqr(e) <= radiusSq;
        List<LivingEntity> candidates = new ArrayList<>();
        if (this.minTier == Tier.APEX) {
            for (Entity e : TripodCompat.tripods(this.unit.level())) {
                if (e instanceof LivingEntity living && wanted.test(living)) candidates.add(living);
            }
        } else {
            candidates = this.unit.level().getEntitiesOfClass(
                    LivingEntity.class, new AABB(this.unit.blockPosition()).inflate(radius), wanted);
        }

        LivingEntity best = null;
        Tier bestTier = currentTier;
        double bestDistSq = Double.MAX_VALUE;
        for (LivingEntity candidate : candidates) {
            Tier tier = SoftEnemyTargeting.classify(candidate);
            // Higher tier always wins outright; within the same tier, nearest wins.
            if (tier.ordinal() < bestTier.ordinal()) continue;
            double distSq = this.unit.distanceToSqr(candidate);
            if (tier == bestTier && distSq >= bestDistSq) continue;
            // LOS last and only on the running best: the raycast is the expensive part and
            // most candidates never become the best.
            if (EasyMode.vehicleTargetRequireLos()
                    && !this.unit.getSensing().hasLineOfSight(candidate)) {
                continue;
            }
            best = candidate;
            bestTier = tier;
            bestDistSq = distSq;
        }
        return best;
    }

    /**
     * Mirrors {@link CrewTargetPriorityGoal#scanRadius()}: a mounted crew scans the vehicle
     * cylinder's radius, an unmounted unit is bounded by SEM's own follow range.
     */
    private double scanRadius() {
        return EasyMode.scanRadius(this.unit, this.unit.getVehicle() != null
                ? SewvConfig.VEHICLE_TARGET_SCAN_RADIUS.get()
                : this.unit.getAttributeValue(Attributes.FOLLOW_RANGE));
    }
}
