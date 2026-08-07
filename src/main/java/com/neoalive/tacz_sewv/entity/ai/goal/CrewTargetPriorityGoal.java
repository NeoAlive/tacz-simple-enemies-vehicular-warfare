package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;
import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.support.MortarSupport;
import com.neoalive.tacz_sewv.entity.ai.support.TowSupport;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleMortarSupport;

/**
 * Doctrine targeting for crew-served weapons and mounted vehicle crews: a mortar shoots
 * troops before monsters, a TOW / tank / IFV / heli / ship prefers vehicles before soft
 * contacts, and an AntiAir platform prefers airborne riders.
 *
 * <p><b>Why this has to be its own goal at priority 1.</b> SEM's target ladder looks like a
 * preference but is not one at the bottom. For a PMC unit it is:
 *
 * <pre>
 *   0  AttackSpecificTargetGoal        (the radio fire mission)
 *   1  NoPlayerHurtByTargetGoal        (retaliation)
 *   2  NearestAttackableTargetGoal(USunitEntity)
 *   2  NearestAttackableTargetGoal(RUunitEntity)
 *   2  NearestAttackableTargetGoal(Monster)     &lt;-- catch-all, SAME priority as troops
 *   3  NearestAttackableTargetGoal(Zombie / Skeleton / AbstractIllager)
 * </pre>
 *
 * Vanilla's {@code WrappedGoal.canBeReplacedBy} only lets a STRICTLY higher-priority goal
 * take a held flag, so once that priority-2 catch-all locks a zombie, the priority-2 troop
 * goals can never take TARGET off it — the crew stays on the zombie until it dies. RU/US
 * ladders have the same shape. Nothing at priority 2 or 3 can fix this from the inside,
 * which is why this sits at <b>1</b>: high enough to preempt every scan in the ladder
 * (including {@link VehicleTargetScanGoal}'s mid-fight soft lock), low enough that a radio
 * fire mission (priority 0) still overrules the crew's own opinion.
 *
 * <p>It ties with retaliation at priority 1, so whichever starts first holds — a deliberate
 * draw. A crew already shelling troops should not drop them because a stray zombie hit it,
 * and a crew that is being shot has fair reason to answer.
 *
 * <p>Mounted general crews use {@link Doctrine#ARMOR}: if the current lock is soft and an
 * armor rider is in range, this goal starts and steals TARGET. Already on armor → stays put.
 * With no preferred contact in reach the goal never starts, TARGET falls back through the
 * normal ladder / vehicle cylinder scan.
 */
public class CrewTargetPriorityGoal extends Goal {

    /** Acquired targets are held past the scan edge so an edge-walker doesn't flicker. */
    private static final double DROP_MULT = 1.5;

    private final AbstractUnit unit;
    private LivingEntity pendingTarget;
    private int scanCooldown;

    public CrewTargetPriorityGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.TARGET));
    }

    /** What this crew's weapon is for, or null when the unit has no priority doctrine. */
    private Doctrine doctrine() {
        if (TowSupport.isCrewing(this.unit)) return Doctrine.ARMOR;
        if (MortarSupport.hasMortarClaim(this.unit)) return Doctrine.TROOPS;
        if (VehicleMortarSupport.isCrewing(this.unit)) return Doctrine.TROOPS;
        if (this.unit.getVehicle() instanceof VehicleEntity v && HullFacts.isAntiAirHull(v)) {
            return Doctrine.AIR;
        }
        // Any other mounted SBW crew (tank, IFV, heli, ship, …): armor outranks soft mid-fight.
        if (this.unit.getVehicle() instanceof VehicleEntity) return Doctrine.ARMOR;
        return null;
    }

    private enum Doctrine {
        /** TOW / general vehicle crew: prefer hull riders over soft contacts. */
        ARMOR,
        /** Mortar: an area weapon, and troops are what it is for. */
        TROOPS,
        /** AA platform: prefer crews riding HELICOPTER / AIRCRAFT hulls. */
        AIR
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;

        Doctrine doctrine = doctrine();
        if (doctrine == null) return false;
        if (!VehicleTargeting.ordersAllowAutoTargets(this.unit)) return false;

        // Already on the right kind of target — nothing to improve, so don't hold TARGET
        // (and don't pay for a scan) just to re-pick what the crew is already shooting.
        LivingEntity current = this.unit.getTarget();
        if (current != null && current.isAlive() && preferred(doctrine, current)) return false;

        if (this.scanCooldown > 0) {
            this.scanCooldown--;
            return false;
        }
        this.scanCooldown = SewvConfig.VEHICLE_TARGET_SCAN_INTERVAL_TICKS.get();

        this.pendingTarget = scan(doctrine);
        return this.pendingTarget != null;
    }

    @Override
    public void start() {
        this.unit.setTarget(this.pendingTarget);
        this.pendingTarget = null;
    }

    @Override
    public boolean canContinueToUse() {
        Doctrine doctrine = doctrine();
        if (doctrine == null) return false;
        if (!VehicleTargeting.ordersAllowAutoTargets(this.unit)) return false;

        LivingEntity target = this.unit.getTarget();
        if (target == null || !target.isAlive()) return false;
        // The crew moved on to something else (retaliation, an order) — let it go rather
        // than drag it back; this goal's job is the pick, not custody.
        if (!preferred(doctrine, target)) return false;

        double drop = scanRadius() * DROP_MULT;
        return this.unit.distanceToSqr(target) <= drop * drop;
    }

    @Override
    public void stop() {
        // Vanilla TargetGoal contract: releasing TARGET clears the target, so whichever
        // goal picks it up next (retaliation, the ladder's own scans) starts clean.
        this.unit.setTarget(null);
        this.pendingTarget = null;
    }

    /** Nearest valid target of the preferred class, or null if there is none in reach. */
    private LivingEntity scan(Doctrine doctrine) {
        double radius = scanRadius();
        double radiusSq = radius * radius;

        List<LivingEntity> candidates = this.unit.level().getEntitiesOfClass(
                LivingEntity.class, new AABB(this.unit.blockPosition()).inflate(radius),
                e -> preferred(doctrine, e) && isValidTarget(e)
                        && this.unit.distanceToSqr(e) <= radiusSq);

        LivingEntity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (LivingEntity candidate : candidates) {
            double distSq = this.unit.distanceToSqr(candidate);
            if (distSq >= bestDistSq) continue;
            // LOS last and only on the running best: the raycast is the expensive part and
            // most candidates never become the best.
            if (SewvConfig.VEHICLE_TARGET_REQUIRE_LOS.get()
                    && !this.unit.getSensing().hasLineOfSight(candidate)) {
                continue;
            }
            best = candidate;
            bestDistSq = distSq;
        }
        return best;
    }

    /** Is this the kind of target the crew's weapon is for? */
    private static boolean preferred(Doctrine doctrine, LivingEntity target) {
        return switch (doctrine) {
            // A VehicleEntity is not a LivingEntity and can never be the target itself —
            // the AI targets the crew riding it, so "is armor" means "is riding a hull".
            case ARMOR -> target.getVehicle() instanceof VehicleEntity;
            case TROOPS -> target instanceof AbstractUnit;
            case AIR -> isAirborneRider(target);
        };
    }

    /** True when the living target is riding a HELICOPTER or AIRCRAFT engine hull. */
    private static boolean isAirborneRider(LivingEntity target) {
        if (!(target.getVehicle() instanceof VehicleEntity v)) return false;
        return HullFacts.isPlaneHull(v) || HullFacts.isHelicopterHull(v);
    }

    /**
     * Mirrors {@link VehicleTargetScanGoal}'s faction doctrine, which is SEM's own: PMC
     * crews fight RU/US and hostile mobs but never players or other PMC; RU/US fight
     * players, the opposing faction and hostile mobs. Kept in step with that goal
     * deliberately — a crew must not prefer a target its own scan would refuse.
     */
    private boolean isValidTarget(LivingEntity e) {
        if (e == this.unit || !e.isAlive() || !e.isAttackable()) return false;
        if (VehicleTargeting.isNonHostile(this.unit, e)) return false;
        if (this.unit.getVehicle() != null && e.getVehicle() == this.unit.getVehicle()) {
            return false; // riding our own hull — crewmate, or a hugger the tube can't reach
        }

        if (this.unit instanceof PmcUnitEntity) {
            if (e instanceof PmcUnitEntity) {
                if (VehicleTargeting.isDiplomacyEnemy(this.unit, e)) {
                    SewvDiag.scan(
                            "CrewTargetPriorityGoal.isValidTarget ALLOW diplomacyEnemy Pmc "
                                    + "unit={}#{} cand={}#{}",
                            this.unit.getClass().getSimpleName(), this.unit.getId(),
                            e.getClass().getSimpleName(), e.getId());
                    return e instanceof Enemy;
                }
                SewvDiag.scan(
                        "CrewTargetPriorityGoal.isValidTarget REJECT hardPmcExclusion "
                                + "unit={}#{} cand={}#{} isNonHostile=false → DROP (ALLY/NEUTRAL/unresolved)",
                        this.unit.getClass().getSimpleName(), this.unit.getId(),
                        e.getClass().getSimpleName(), e.getId());
                return false;
            }
            return e instanceof Enemy;
        }
        if (e instanceof Player p) return !p.isCreative() && !p.isSpectator();
        return e instanceof Enemy;
    }

    /**
     * A mounted TOW crew scans the vehicle cylinder's radius; an unmounted mortar crew is
     * bounded by SEM's own follow range, which is the reach it would have had anyway (and
     * why the radio exists at all — see {@link FireMissionSupport}).
     */
    private double scanRadius() {
        return this.unit.getVehicle() != null
                ? SewvConfig.VEHICLE_TARGET_SCAN_RADIUS.get()
                : this.unit.getAttributeValue(Attributes.FOLLOW_RANGE);
    }

}
