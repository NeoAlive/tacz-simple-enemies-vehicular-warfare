package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.config.EasyMode;
import com.neoalive.tacz_sewv.config.EasyModePlayerEvents;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.sensor.CombatantIndex;
import com.neoalive.tacz_sewv.entity.ai.support.MortarSupport;
import com.neoalive.tacz_sewv.entity.ai.support.TowSupport;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleCrew;
import com.neoalive.tacz_sewv.entity.ai.targeting.AssignedTargets;
import com.neoalive.tacz_sewv.entity.ai.targeting.TargetRanker;
import com.neoalive.tacz_sewv.entity.ai.targeting.TargetStack;

/**
 * The priority stack for a unit on foot. Priority 1, TARGET: above SEM's nearest-first scans,
 * which stay as plain acquisition. Crews rank inside {@link VehicleTargetScanGoal} instead.
 *
 * <p>Custody rules, all load-bearing:
 * <ul>
 *   <li>retaliation shares priority 1 and cannot preempt, so this lets go itself when a hostile
 *       other than the target hurt the unit;</li>
 *   <li>an order, a fire mission or retaliation that changes the target ends custody, and
 *       {@link #stop} clears the target only when it is still the one this goal set;</li>
 *   <li>a re-rank never moves off an assigned target or a recent attacker ({@link AssignedTargets}).</li>
 * </ul>
 */
public class StackTargetGoal extends Goal {

    private static final double DROP_MULT = 1.5;
    /** A target out of sight this long (game ticks) is released. */
    private static final long UNSEEN_TICKS = 60;

    private final AbstractUnit unit;
    @Nullable private LivingEntity pending;
    @Nullable private LivingEntity chosen;
    private long nextCheck = Long.MIN_VALUE;
    private long lastSeen;

    public StackTargetGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.TARGET));
    }

    private boolean applies() {
        if (this.unit.level().isClientSide()) return false;
        if (VehicleCrew.suppressOnFootAi(this.unit)) return false;
        if (TowSupport.isCrewing(this.unit) || MortarSupport.hasMortarClaim(this.unit)) return false;
        return VehicleTargeting.ordersAllowAutoTargets(this.unit);
    }

    @Override
    public boolean canUse() {
        // A recent hostile attacker is retaliation's to answer, and both sit at priority 1.
        if (!applies() || AssignedTargets.held(this.unit) || AssignedTargets.hurtByOther(this.unit)) return false;
        long now = this.unit.level().getGameTime();
        if (now < this.nextCheck) return false;
        this.nextCheck = now + recheckTicks();
        LivingEntity current = this.unit.getTarget();
        this.pending = pick(current != null && current.isAlive() ? current : null);
        return this.pending != null;
    }

    @Override
    public void start() {
        this.unit.setTarget(this.pending);
        this.chosen = this.unit.getTarget() == this.pending ? this.pending : null;
        this.pending = null;
        this.lastSeen = this.unit.level().getGameTime();
    }

    @Override
    public boolean canContinueToUse() {
        if (this.chosen == null || !applies()) return false;
        LivingEntity target = this.unit.getTarget();
        if (target != this.chosen || !target.isAlive()) return false;
        if (AssignedTargets.hurtByOther(this.unit)) return false;
        if (!VehicleTargeting.isValidHostileTarget(this.unit, target)) return false;
        double drop = scanRadius() * DROP_MULT;
        if (this.unit.distanceToSqr(target) > drop * drop) return false;
        long now = this.unit.level().getGameTime();
        if (this.unit.getSensing().hasLineOfSight(target)) this.lastSeen = now;
        return now - this.lastSeen <= UNSEEN_TICKS;
    }

    @Override
    public void tick() {
        long now = this.unit.level().getGameTime();
        if (now < this.nextCheck || AssignedTargets.held(this.unit)) return;
        this.nextCheck = now + recheckTicks();
        LivingEntity better = pick(this.chosen);
        if (better != null && better != this.chosen) {
            this.unit.setTarget(better);
            if (this.unit.getTarget() == better) {
                this.chosen = better;
                this.lastSeen = now;
            }
        }
    }

    @Override
    public void stop() {
        if (this.chosen != null && this.unit.getTarget() == this.chosen) this.unit.setTarget(null);
        this.chosen = null;
        this.pending = null;
    }

    /**
     * The best visible candidate that beats {@code current} by the switch rule, or, with no current
     * target, the best visible one. Line of sight is checked in rank order only until a pass.
     */
    @Nullable
    private LivingEntity pick(@Nullable LivingEntity current) {
        List<LivingEntity> candidates = candidates();
        if (current != null && !candidates.contains(current)) candidates.add(current);
        if (candidates.isEmpty()) return null;
        List<TargetRanker.Candidate<LivingEntity>> ranked =
                TargetStack.rank(this.unit, candidates, this.unit::distanceTo);
        TargetRanker.Candidate<LivingEntity> cur = null;
        for (TargetRanker.Candidate<LivingEntity> c : ranked) {
            if (c.ref == current) cur = c;
        }
        boolean needLos = EasyMode.vehicleTargetRequireLos();
        for (TargetRanker.Candidate<LivingEntity> c : ranked) {
            if (c.ref == current) return null;
            if (cur != null && !TargetRanker.shouldSwitch(c, cur)) return null;
            if (!needLos || this.unit.getSensing().hasLineOfSight(c.ref)) return c.ref;
        }
        return null;
    }

    private List<LivingEntity> candidates() {
        List<LivingEntity> out = new ArrayList<>();
        CombatantIndex.Snapshot snap = CombatantIndex.snapshot(this.unit.level());
        if (snap == null) return out;
        double radius = scanRadius();
        double radiusSq = radius * radius;
        for (CombatantIndex.Entry e : snap.query(this.unit.getX(), this.unit.getZ(), radius,
                this.unit.getY() - radius, this.unit.getY() + radius)) {
            add(out, e.living, radiusSq);
        }
        for (CombatantIndex.Entry e : snap.hulls()) {
            if (Math.abs(e.x - this.unit.getX()) > radius || Math.abs(e.z - this.unit.getZ()) > radius) continue;
            add(out, e.living, radiusSq);
        }
        out.removeIf(e -> e instanceof Player p && EasyModePlayerEvents.refusesPlayer(this.unit, p));
        EasyModePlayerEvents.preferNonPlayers(this.unit, out);
        return out;
    }

    private void add(List<LivingEntity> out, @Nullable LivingEntity e, double radiusSq) {
        if (e == null || !e.isAlive() || out.contains(e)) return;
        if (this.unit.distanceToSqr(e) > radiusSq) return;
        if (!VehicleTargeting.isValidHostileTarget(this.unit, e)) return;
        out.add(e);
    }

    private double scanRadius() {
        return EasyMode.scanRadius(this.unit, this.unit.getAttributeValue(Attributes.FOLLOW_RANGE));
    }

    private static int recheckTicks() {
        try {
            return SewvConfig.STACK_RECHECK_TICKS.get();
        } catch (Throwable unbaked) {
            return 20;
        }
    }
}
