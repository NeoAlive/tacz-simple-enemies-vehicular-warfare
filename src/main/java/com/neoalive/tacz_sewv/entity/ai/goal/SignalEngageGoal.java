package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.support.PmcDownedSupport;
import com.neoalive.tacz_sewv.fpas.FiringSpots;
import com.neoalive.tacz_sewv.fpas.SignalTasks;

/**
 * A Signal task's walk on foot ({@link SignalTasks}): to a spot with a line of fire on the target,
 * in legs short enough that vanilla's FOLLOW_RANGE-bounded path search always finds one. Priority 1
 * MOVE, so it outranks SEM's cover, chase and maneuver goals for the walk; once there the task
 * flips to ENGAGE, this goal lets go, and SEM fights from the spot with its own cover and rifle.
 * A threat inside {@link #SELF_DEFENCE} blocks also lets go.
 */
public class SignalEngageGoal extends Goal {

    private static final double ARRIVE = 2.5;
    private static final double MAX_LEG = 40.0;
    private static final double SELF_DEFENCE = 6.0;
    private static final long STALL_TICKS = 200;
    private static final long REPATH_TICKS = 20;
    private static final double SPEED = 1.2;

    private final PmcUnitEntity unit;
    private double bestDistSq = Double.MAX_VALUE;
    private long lastProgress;
    private long nextPath;

    public SignalEngageGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide() || !this.unit.isAlive() || this.unit.getVehicle() != null) return false;
        if (!SignalTasks.moving(this.unit) || PmcDownedSupport.isDowned(this.unit)) return false;
        LivingEntity t = this.unit.getTarget();
        return t == null || !t.isAlive() || t.distanceToSqr(this.unit) > SELF_DEFENCE * SELF_DEFENCE;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.bestDistSq = Double.MAX_VALUE;
        this.lastProgress = this.unit.level().getGameTime();
        this.nextPath = this.lastProgress;
    }

    @Override
    public void stop() {
        this.unit.getNavigation().stop();
    }

    @Override
    public void tick() {
        SignalTasks.Task task = SignalTasks.get(this.unit);
        if (task == null) return;
        long now = this.unit.level().getGameTime();
        Vec3 spot = SignalTasks.spot(this.unit);
        // No spot with a line of fire: close on the track and let the sweep catch the first clear shot.
        Vec3 goal = spot != null ? spot : task.track();
        double d = this.unit.position().distanceToSqr(goal.x, this.unit.getY(), goal.z);
        if (spot != null && d < ARRIVE * ARRIVE) {
            SignalTasks.arrive(this.unit);
            this.unit.getNavigation().stop();
            return;
        }
        if (d < this.bestDistSq - 1.0) {
            this.bestDistSq = d;
            this.lastProgress = now;
        } else if (now - this.lastProgress > STALL_TICKS) {
            SignalTasks.stalled(this.unit);
            this.bestDistSq = Double.MAX_VALUE;
            this.lastProgress = now;
            return;
        }
        if (now < this.nextPath && !this.unit.getNavigation().isDone()) return;
        this.nextPath = now + REPATH_TICKS;
        double[] leg = FiringSpots.leg(this.unit.getX(), this.unit.getZ(), goal.x, goal.z, MAX_LEG);
        int bx = (int) Math.floor(leg[0]), bz = (int) Math.floor(leg[1]);
        double y = this.unit.level().hasChunk(bx >> 4, bz >> 4)
                ? this.unit.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) : this.unit.getY();
        this.unit.getNavigation().moveTo(leg[0], y, leg[1], SPEED);
    }
}
