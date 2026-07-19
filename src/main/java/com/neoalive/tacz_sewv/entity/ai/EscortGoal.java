package com.neoalive.tacz_sewv.entity.ai;

import com.neoalive.tacz_sewv.bridge.IEscort;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.EnumSet;

/**
 * Keeps a unit beside a chosen entity — "FOLLOW_ME, but the leader is whatever the player
 * left-clicked" (a vehicle, in practice). Modelled on SEM's {@code AttackSpecificTargetGoal}, which
 * is the mod's own "resolve an arbitrary entity by network id every few ticks and act on it"
 * pattern — but this one <b>moves to</b> the entity instead of attacking it.
 *
 * <h2>Why not SEM's own follow</h2>
 * {@code CommanderOrderGoal.performFollowOwner} hard-resolves the leader as
 * {@code getPlayerByUUID(getOwnerUUID())} and shares that path with HOLD/MOVE/FORM, so redirecting
 * it would hijack every positional order. A standalone goal keyed on its own transient id
 * ({@link IEscort}) touches nothing else.
 *
 * <h2>Priority 1, and it does NOT yield to combat — both deliberate</h2>
 * SEM's owner-follow ({@code CommanderOrderGoal}) sits at priority 3 and holds the MOVE flag for
 * <em>any</em> order, and the chase goal ({@code MoveToAttackRangeGoal}) is also priority 3 MOVE. To
 * keep an escort glued rather than dragged off, this must outrank both — hence priority 1 (still
 * below the priority-0 survival goals: Float, TacticalManager). And it keeps running through combat
 * rather than standing down when the unit has a target: holding MOVE at priority 1 suppresses the
 * chase goal, so the unit fights from beside the vehicle (the rifle goal is a separate LOOK flag and
 * fires concurrently) instead of running 90 blocks after an enemy. That is the bodyguard behaviour
 * asked for.
 */
public class EscortGoal extends Goal {

    // Mirrors SEM's own follow tuning (CommanderOrderGoal 1.1 / 10 / 3), a touch tighter — a
    // bodyguard should sit closer to the VIP than a squad trails its commander.
    private static final double SPEED = 1.15;
    private static final double START_FOLLOW_DISTANCE = 8.0;
    private static final double STOP_FOLLOW_DISTANCE = 3.0;
    // Re-path cadence. The escorted entity moves, so the destination is refreshed — but not every
    // tick (that thrashes the navigator); a few blocks of lag behind a moving vehicle is fine.
    private static final int REPATH_INTERVAL = 10;

    private final PmcUnitEntity unit;
    private Entity escortTarget;
    private int repathCooldown;

    public EscortGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    private int escortId() {
        return ((IEscort) this.unit).tacz_sewv$getEscortTargetId();
    }

    private void clearEscort() {
        ((IEscort) this.unit).tacz_sewv$setEscortTargetId(-1);
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (this.unit.isPassenger()) return false; // riding something itself — not on foot to escort
        if (escortId() == -1) return false;

        Entity target = this.unit.level().getEntity(escortId());
        if (target == null || !target.isAlive() || target == this.unit) {
            clearEscort(); // the VIP is gone or nonsense — free the unit
            return false;
        }
        this.escortTarget = target;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return escortId() != -1
                && this.escortTarget != null
                && this.escortTarget.isAlive()
                && !this.unit.isPassenger();
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
    }

    @Override
    public void stop() {
        this.escortTarget = null;
        this.unit.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (this.escortTarget == null) return;

        // Always face the VIP so the unit reads as attending it, and so its body yaw is sane when
        // it isn't actively pathing.
        this.unit.getLookControl().setLookAt(this.escortTarget, 30.0F, 30.0F);

        double distSq = this.unit.distanceToSqr(this.escortTarget);
        if (distSq <= STOP_FOLLOW_DISTANCE * STOP_FOLLOW_DISTANCE) {
            // Close enough — hold, so the unit doesn't jitter into the hull.
            this.unit.getNavigation().stop();
            return;
        }
        if (distSq <= START_FOLLOW_DISTANCE * START_FOLLOW_DISTANCE && this.unit.getNavigation().isDone()) {
            return; // inside the follow band and already parked — nothing to do
        }

        // Beyond the band (or still parked short of it): re-path toward the moving VIP, throttled.
        if (--this.repathCooldown <= 0) {
            this.repathCooldown = REPATH_INTERVAL;
            this.unit.getNavigation().moveTo(this.escortTarget, SPEED);
        }
    }
}
