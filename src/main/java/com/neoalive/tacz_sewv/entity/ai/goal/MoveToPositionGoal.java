package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

/**
 * Hard MOVE_TO_POSITION stick. SEM's {@code CommanderOrderGoal} (prio 3) loses MOVE to SeekCover
 * (prio 2) and bails on a live target; multi-select also columns index&gt;0 behind the point man
 * instead of the clicked point. Same shape as {@link FollowCommanderGoal}: priority 1, MOVE, runs
 * through combat, paths every ordered unit to {@code getMoveToTarget()}. Releases once arrived
 * (SEM's {@code distSqr < 2.5} gate) so local cover can take over at the destination.
 */
public class MoveToPositionGoal extends Goal {

    /** Match SEM {@code CommanderOrderGoal.performMoveToPosition} arrival. */
    private static final double ARRIVE_SQ = 2.5D;
    private static final double SPEED = 1.2;
    private static final int REPATH_INTERVAL = 8;

    private final PmcUnitEntity unit;
    private Vec3 destination;
    private int repathCooldown;

    public MoveToPositionGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (this.unit.isPassenger()) return false;
        if (this.unit.getOrder() != OrderType.MOVE_TO_POSITION) return false;

        Vec3 dest = this.unit.getMoveToTarget();
        if (dest == null || dest.equals(Vec3.ZERO)) return false;
        if (this.unit.distanceToSqr(dest) < ARRIVE_SQ) return false;

        this.destination = dest;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.unit.getOrder() != OrderType.MOVE_TO_POSITION) return false;
        if (this.unit.isPassenger()) return false;
        Vec3 dest = this.unit.getMoveToTarget();
        if (dest == null || dest.equals(Vec3.ZERO)) return false;
        this.destination = dest;
        return this.unit.distanceToSqr(dest) >= ARRIVE_SQ;
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
    }

    @Override
    public void stop() {
        this.destination = null;
        this.unit.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (this.destination == null) return;

        ((AbstractUnit) this.unit).releaseMovementLock();

        LivingEntity aim = this.unit.getTarget();
        if (aim != null && aim.isAlive()) {
            this.unit.getLookControl().setLookAt(aim, 30.0F, 30.0F);
        } else {
            this.unit.getLookControl().setLookAt(
                    this.destination.x, this.destination.y, this.destination.z, 30.0F, 30.0F);
        }

        if (--this.repathCooldown <= 0) {
            this.repathCooldown = REPATH_INTERVAL;
            this.unit.getNavigation().moveTo(
                    this.destination.x, this.destination.y, this.destination.z, SPEED);
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
