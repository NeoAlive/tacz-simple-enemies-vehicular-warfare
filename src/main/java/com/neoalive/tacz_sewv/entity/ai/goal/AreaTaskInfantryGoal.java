package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.ITerritoryPost;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;

/**
 * Patrol / Search &amp; Destroy on foot: walk the same area task a crew drives
 * ({@link PatrolSupport#currentWaypoint}, with no vehicle). Contact sharing, the area leash and
 * every stand-down already work off that task, so this goal only owns movement.
 * Priority 1 + MOVE, like {@link SweepInfantryGoal}, so {@code MoveToAttackRangeGoal}'s chase cannot
 * pull the unit off its area; it fights from the route through SEM's LOOK-flag rifle goal.
 */
public class AreaTaskInfantryGoal extends Goal {

    /** Re-issue the path this often; the waypoint itself only changes when the task rolls one. */
    private static final int REPATH_TICKS = 20;

    private final PmcUnitEntity unit;
    private int repath;

    public AreaTaskInfantryGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        // A Territory post owns MOVE at the same priority; posting clears the task, this is the backstop.
        return !this.unit.isPassenger()
                && ((IVehiclePatrol) this.unit).sewv$getPatrolOrigin() != null
                && !((ITerritoryPost) this.unit).sewv$hasTerritoryPost();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        this.repath = 0;
    }

    @Override
    public void stop() {
        this.unit.getNavigation().stop();
    }

    @Override
    public void tick() {
        // Advances legs and ends a finished search, so it must be read every tick, not only on repath.
        BlockPos wp = PatrolSupport.currentWaypoint(this.unit, null);
        if (wp == null) return;
        if (--this.repath > 0 && !this.unit.getNavigation().isDone()) return;
        this.repath = REPATH_TICKS;
        this.unit.getNavigation().moveTo(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5, 1.0);
    }
}
