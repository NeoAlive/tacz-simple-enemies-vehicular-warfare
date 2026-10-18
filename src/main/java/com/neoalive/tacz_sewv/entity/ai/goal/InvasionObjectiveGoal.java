package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.invasion.CaptureOrderSupport;
import com.neoalive.tacz_sewv.invasion.InvasionTags;

/**
 * Walks an invasion rifleman to its objective — the on-foot half of the capture pipeline, whose
 * vehicle half is a destination in {@code VehicleTargeting.resolveDestination}. Where it walks is
 * {@link CaptureOrderSupport#infantryDestination}: the next point to take for a capturing rifleman,
 * the point most worth holding for a contest-only one.
 *
 * <h2>When it holds MOVE</h2>
 * Priority 1, MOVE, and only while there is walking to do: off station, or on station but away from
 * its slot with nobody to fight. It lets go in two cases, both on purpose:
 * <ul>
 * <li>an enemy within {@link #ENGAGE_RANGE}: SEM's cover and approach goals fight the local threat,
 *     then the rifleman resumes. A further target is shot at on the move (the rifle goal is LOOK).</li>
 * <li>settled on its slot: SEM's local cover can take over. What keeps it from wandering off the
 *     point is {@code FollowLeash}, which treats an on-station rifleman as leashed, so
 *     {@code MoveToAttackRangeGoal}'s 90-block chase never starts.</li>
 * </ul>
 * Each rifleman holds a slot seeded by its entity id inside the zone, so a section spreads across
 * the point instead of stacking on the block.
 */
public class InvasionObjectiveGoal extends Goal {

    private static final double SPEED = 1.1;
    /** An enemy this close is fought where it is; further ones are shot at on the way. */
    private static final double ENGAGE_RANGE = 20.0;
    private static final double SETTLE_DISTANCE = 2.0;
    /** Contest-only riflemen re-judge which point needs them this often (game ticks). */
    private static final long REPICK_TICKS = 100;
    /** Goal ticks between path refreshes (goals tick every other game tick). */
    private static final int REPATH_INTERVAL = 20;
    private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));

    private final AbstractUnit unit;
    @Nullable
    private BlockPos slot;
    private BlockPos slotFor;
    private long nextRepickAt = Long.MIN_VALUE;
    private int repathCooldown;

    public InvasionObjectiveGoal(AbstractUnit unit) {
        this.unit = unit;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!this.unit.getPersistentData().getBoolean(InvasionTags.SPAWN)) return false;
        if (!CaptureOrderSupport.isInvasionInfantry(this.unit)) return false;
        if (engagedNearby()) return false;
        BlockPos dest = destination();
        if (dest == null) return false;
        return !settled(dest);
    }

    @Override
    public boolean canContinueToUse() {
        if (!CaptureOrderSupport.isInvasionInfantry(this.unit) || engagedNearby()) return false;
        BlockPos dest = destination();
        return dest != null && !settled(dest);
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
    }

    @Override
    public void stop() {
        this.unit.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (this.slot == null) return;
        if (--this.repathCooldown > 0) return;
        this.repathCooldown = REPATH_INTERVAL;
        this.unit.getNavigation().moveTo(this.slot.getX() + 0.5, this.slot.getY(), this.slot.getZ() + 0.5, SPEED);
    }

    private boolean engagedNearby() {
        LivingEntity target = this.unit.getTarget();
        return target != null && target.isAlive() && this.unit.distanceToSqr(target) < ENGAGE_RANGE * ENGAGE_RANGE;
    }

    @Nullable
    private BlockPos destination() {
        long now = this.unit.level().getGameTime();
        boolean repick = now >= this.nextRepickAt;
        if (repick) this.nextRepickAt = now + REPICK_TICKS;
        BlockPos dest = CaptureOrderSupport.infantryDestination(this.unit, repick);
        if (dest == null) {
            this.slot = null;
            return null;
        }
        if (!dest.equals(this.slotFor)) {
            this.slotFor = dest;
            this.slot = slotIn(dest, CaptureOrderSupport.zoneRadius(this.unit.level(), dest));
            this.repathCooldown = 0;
        }
        return dest;
    }

    private boolean settled(BlockPos dest) {
        return this.slot != null
                && this.unit.distanceToSqr(this.slot.getX() + 0.5, this.slot.getY(), this.slot.getZ() + 0.5)
                        < SETTLE_DISTANCE * SETTLE_DISTANCE;
    }

    /** A point inside the zone, fixed per entity id (golden-angle spiral, so ids spread evenly). */
    private BlockPos slotIn(BlockPos centre, int radius) {
        int id = this.unit.getId();
        double angle = id * GOLDEN_ANGLE;
        double reach = Math.max(1.0, radius * 0.6);
        double r = reach * (0.25 + 0.75 * ((id * 0.618034) % 1.0));
        int x = centre.getX() + (int) Math.round(Math.cos(angle) * r);
        int z = centre.getZ() + (int) Math.round(Math.sin(angle) * r);
        int y = this.unit.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }
}
