package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.util.SoldierState;

import com.neoalive.tacz_sewv.entity.ai.support.CoverHoldOrders;
import com.neoalive.tacz_sewv.entity.ai.support.CoverHoldSearch;
import com.neoalive.tacz_sewv.entity.ai.support.PmcDownedSupport;
import com.neoalive.tacz_sewv.entity.ai.support.SuppressOrders;

/**
 * Cover and Hold on foot ({@link CoverHoldOrders}). Walks to the cover SEM's own finder picked, then
 * hands it back to SEM ({@code setCoverBlock} + {@code HOLD_COVER}) so SEM's peek and rifle work from it.
 *
 * <p>Priority 1, MOVE. On the way it shoots on the move (SEM's rifle goal only takes LOOK) and only
 * a target inside {@link SuppressOrders#FOOT_SELF_DEFENCE} stops the walk. Holding, it runs only while
 * the unit has no target (to stay on and face the spot) or has strayed past {@link #LEASH}. With a
 * target in reach it declines in {@code canUse}, not just {@code canContinueToUse}: SEM's peek sits at
 * priority 4 and would lose MOVE straight back to a goal that kept wanting it.
 */
public class CoverHoldGoal extends Goal {

    private static final double ARRIVE = 2.5;
    private static final double SETTLE = 1.5;
    /** How far a peek or a shove may take a holding unit before it walks back. */
    private static final double LEASH = 6.0;
    /** Game ticks without getting closer before the walk gives up and holds where it stands. */
    private static final long STALL_TICKS = 600;
    private static final long REPATH_TICKS = 20;
    private static final double SPEED = 1.1;

    private final PmcUnitEntity unit;
    private double bestDistSq = Double.MAX_VALUE;
    private long lastProgress = Long.MIN_VALUE;
    private long nextPath;

    public CoverHoldGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide() || !this.unit.isAlive()) return false;
        if (!CoverHoldOrders.has(this.unit) || PmcDownedSupport.isDowned(this.unit)) return false;
        CoverHoldOrders.Order order = CoverHoldOrders.active(this.unit, this.unit.level().getGameTime());
        if (order == null) return false;
        if (!order.holding()) return !SuppressOrders.selfDefence(this.unit, SuppressOrders.FOOT_SELF_DEFENCE);
        if (order.spot() == null) return false;
        if (this.unit.distanceToSqr(Vec3.atBottomCenterOf(order.spot())) > LEASH * LEASH) return true;
        return liveTarget() == null;
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
        long now = this.unit.level().getGameTime();
        CoverHoldOrders.Order order = CoverHoldOrders.active(this.unit, now);
        if (order == null) return;
        BlockPos spot = order.spot();
        if (spot == null) {
            BlockPos origin = BlockPos.containing(order.originX(), this.unit.getY(), order.originZ());
            CoverHoldSearch.Result r = CoverHoldSearch.search(this.unit, origin, order.threat());
            if (r == null) return; // SEM's search budget is spent this tick
            CoverHoldOrders.setSpot(this.unit, r.spot(), !r.cover());
            return;
        }
        Vec3 at = Vec3.atBottomCenterOf(spot);
        double d = this.unit.distanceToSqr(at);
        if (!order.holding()) {
            if (d < ARRIVE * ARRIVE) {
                arrive(spot, order.open(), now);
                return;
            }
            if (d < this.bestDistSq - 1.0) {
                this.bestDistSq = d;
                this.lastProgress = now;
            } else if (now - this.lastProgress > STALL_TICKS) {
                // Cannot get there: hold where it stands, reported as in the open.
                CoverHoldOrders.setSpot(this.unit, this.unit.blockPosition(), true);
                arrive(this.unit.blockPosition(), true, now);
                return;
            }
            walk(at, now);
            return;
        }
        if (d > SETTLE * SETTLE) {
            walk(at, now);
            return;
        }
        this.unit.getNavigation().stop();
        Vec3 threat = order.threat();
        this.unit.getLookControl().setLookAt(threat.x, threat.y, threat.z);
    }

    private void walk(Vec3 at, long now) {
        if (now < this.nextPath && !this.unit.getNavigation().isDone()) return;
        this.nextPath = now + REPATH_TICKS;
        this.unit.getNavigation().moveTo(at.x, at.y, at.z, SPEED);
    }

    /** The handshake: the spot becomes SEM's cover block, and the hold clock starts. */
    private void arrive(BlockPos spot, boolean open, long now) {
        this.unit.getNavigation().stop();
        // In the open there is no cover block, and a stale one from SEM's last search would send peeks off it.
        this.unit.setCoverBlock(open ? null : spot);
        if (!open) this.unit.setSoldierState(SoldierState.HOLD_COVER);
        CoverHoldOrders.arrive(this.unit, now);
    }

    @Nullable
    private LivingEntity liveTarget() {
        LivingEntity t = this.unit.getTarget();
        return t != null && t.isAlive() ? t : null;
    }
}
