package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.fob.FobDebug;
import com.neoalive.tacz_sewv.fob.FobInstance;
import com.neoalive.tacz_sewv.fob.FobManager;
import com.neoalive.tacz_sewv.fob.FobSupport;
import com.neoalive.tacz_sewv.fob.ThreatEvaluator;

/**
 * Scramble response: every stamped unit mans an assigned hull the moment the threat score trips,
 * and picks up the nearest hostile as a target.
 *
 * <p>Like {@link SeekAbandonedVehicleGoal} this <b>writes orders and nothing else</b> — it claims
 * no flags and always answers {@code canUse() == false}, doing its work inside the evaluation.
 * The earlier version held MOVE+TARGET at priority 0 and then never navigated, which parked the
 * whole garrison: SEM's chase goal and {@link FobPatrolGoal} could not acquire MOVE, and the only
 * units that looked like they were doing anything were the ones already shooting from where they
 * stood. Boarding itself is {@link BoardVehicleGoal}'s job and always was.
 *
 * <p>A hull with <b>any</b> free seat counts, not just an empty one — the point of a scramble is
 * that the whole assigned force mounts up, so the second and third man fill out a crew rather than
 * standing beside a tank that already has a driver.
 */
public class FobScrambleGoal extends Goal {

    /** Game ticks between evaluations. The hostile scan is two AABB sweeps, once per unit. */
    private static final int EVAL_INTERVAL = 20;

    private final PmcUnitEntity unit;
    private long nextEval = Long.MIN_VALUE;

    public FobScrambleGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Goal.Flag.class));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (FobSupport.hasRoutePending(this.unit)) return false;
        // A MOVE click outranks mounting up: BoardVehicleGoal navigates without claiming MOVE, so
        // a board order issued here would fight MoveToPositionGoal for the navigation every tick
        // and the unit would jitter between the click and the motor pool.
        if (FobSupport.underPlayerMoveOrder(this.unit)) return false;
        if (!FobSupport.isStamped(this.unit)) return false;
        if (!(this.unit.level() instanceof ServerLevel level)) return false;

        // Spread the scan across the garrison rather than having every unit sweep on the same tick.
        long now = level.getGameTime();
        if (now < this.nextEval) return false;
        this.nextEval = now + EVAL_INTERVAL + (this.unit.getId() % EVAL_INTERVAL);

        FobInstance fob = fob(level);
        if (fob == null || !fob.scrambleActive) return false;

        scramble(level, fob);
        return false;
    }

    private void scramble(ServerLevel level, FobInstance fob) {
        if (this.unit.getTarget() == null) {
            LivingEntity hostile = ThreatEvaluator.nearestHostile(level, fob, this.unit.blockPosition());
            if (hostile != null) {
                this.unit.setTarget(hostile);
            }
        }
        if (!this.unit.isPassenger()) {
            tryBoard(level, fob);
        }
    }

    /**
     * Nearest assigned hull with room. Empty hulls win outright over partly-crewed ones so a
     * scramble spreads across the motor pool instead of piling everyone into whichever tank
     * happens to be closest to the barracks.
     */
    private void tryBoard(ServerLevel level, FobInstance fob) {
        VehicleEntity best = null;
        boolean bestEmpty = false;
        double bestDist = Double.MAX_VALUE;
        for (UUID id : fob.assignedVehicles) {
            if (!(level.getEntity(id) instanceof VehicleEntity hull)) continue;
            if (!hull.isAlive() || hull.isWreck()) continue;
            int occupied = hull.getPassengers().size();
            if (occupied >= Math.max(1, hull.getMaxPassengers())) continue;
            boolean empty = occupied == 0;
            double d = hull.distanceToSqr(this.unit);
            if (best != null && bestEmpty && !empty) continue;
            if (best == null || (empty && !bestEmpty) || d < bestDist) {
                best = hull;
                bestEmpty = empty;
                bestDist = d;
            }
        }
        if (best == null) return;

        IVehicleBoarder boarder = (IVehicleBoarder) this.unit;
        if (boarder.tacz_sewv$isBoarding() && boarder.tacz_sewv$getMountTargetId() == best.getId()) {
            return;
        }
        boarder.tacz_sewv$setMountTargetId(best.getId());
        boarder.tacz_sewv$setBoarding(true);
        boarder.tacz_sewv$setPassengerOnly(false);
        FobDebug.logEntity(this.unit, "scramble — boarding assigned hull {}", best.getId());
    }

    @Nullable
    private FobInstance fob(ServerLevel level) {
        BlockPos cmd = FobSupport.stampPos(this.unit);
        if (cmd == null) return null;
        return FobManager.get(level).getFob(cmd);
    }
}
