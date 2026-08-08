package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

/**
 * Hard FOLLOW_ME stick. SEM's {@code CommanderOrderGoal} sits at priority 3 and bails the moment
 * the unit has a target, so cover (prio 2) and chase (prio 3) always win the fight for MOVE. The
 * getTarget redirect + cover suppress were not enough against SEM 0.1.6's tactical manager, which
 * forces {@code SEEK_COVER} / flanks on every new contact.
 *
 * <p>Same shape as {@link EscortGoal}: priority 1, MOVE, does not yield to combat. The rifle goal
 * (LOOK) still fires concurrently. Only {@link OrderType#FOLLOW_COMMANDER} — FORM / HOLD keep
 * SEM's own goal (and the softer {@link com.neoalive.tacz_sewv.entity.ai.support.FollowLeash}).
 */
public class FollowCommanderGoal extends Goal {

    private static final double SPEED = 1.2;
    /** Tighter than SEM's 10-block start so a walking commander is not abandoned mid-band. */
    private static final double START_FOLLOW_DISTANCE = 6.0;
    private static final double STOP_FOLLOW_DISTANCE = 2.5;
    private static final int REPATH_INTERVAL = 8;

    private final PmcUnitEntity unit;
    private LivingEntity commander;
    private int repathCooldown;

    public FollowCommanderGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (this.unit.isPassenger()) return false;
        if (this.unit.getOrder() != OrderType.FOLLOW_COMMANDER) return false;

        UUID ownerId = this.unit.getOwnerUUID();
        if (ownerId == null || this.unit.getServer() == null) return false;

        ServerPlayer owner = this.unit.getServer().getPlayerList().getPlayer(ownerId);
        if (owner == null || !owner.isAlive() || owner.level() != this.unit.level()) return false;

        this.commander = owner;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.unit.getOrder() == OrderType.FOLLOW_COMMANDER
                && this.commander != null
                && this.commander.isAlive()
                && this.commander.level() == this.unit.level()
                && !this.unit.isPassenger();
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
    }

    @Override
    public void stop() {
        this.commander = null;
        this.unit.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (this.commander == null) return;

        // Drop SEM's cover/flank movement lock so a stuck SEEK_COVER state cannot freeze the
        // navigator while this goal owns MOVE.
        ((AbstractUnit) this.unit).releaseMovementLock();

        LivingEntity aim = this.unit.getTarget();
        if (aim != null && aim.isAlive()) {
            this.unit.getLookControl().setLookAt(aim, 30.0F, 30.0F);
        } else {
            this.unit.getLookControl().setLookAt(this.commander, 30.0F, 30.0F);
        }

        double distSq = this.unit.distanceToSqr(this.commander);
        if (distSq <= STOP_FOLLOW_DISTANCE * STOP_FOLLOW_DISTANCE) {
            this.unit.getNavigation().stop();
            return;
        }
        if (distSq <= START_FOLLOW_DISTANCE * START_FOLLOW_DISTANCE && this.unit.getNavigation().isDone()) {
            return;
        }

        if (--this.repathCooldown <= 0) {
            this.repathCooldown = REPATH_INTERVAL;
            this.unit.getNavigation().moveTo(this.commander, SPEED);
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
