package com.neoalive.tacz_sewv.entity.ai;

import com.neoalive.tacz_sewv.bridge.ISweepInfantry;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.EnumSet;

/**
 * On-foot Sweep &amp; Advance: wander walkable points inside the assigned chunk rectangle.
 * Priority 1 + MOVE so it outranks {@code MoveToAttackRangeGoal}'s 90-block chase off the area.
 * Fighting still happens via SEM's LOOK-flag rifle goal; this only owns movement.
 */
public class SweepInfantryGoal extends Goal {

    private static final int ROTATE_TICKS = 100;
    private static final int PICK_ATTEMPTS = 16;
    private static final double ARRIVE_SQ = 4.0 * 4.0;

    private final PmcUnitEntity unit;

    public SweepInfantryGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return !unit.isPassenger() && ((ISweepInfantry) unit).sewv$hasInfantrySweep();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void stop() {
        unit.getNavigation().stop();
    }

    @Override
    public void tick() {
        ISweepInfantry sweep = (ISweepInfantry) unit;
        long now = unit.level().getGameTime();
        long packed = sweep.sewv$getInfSweepWaypoint();
        BlockPos wp = packed == Long.MIN_VALUE ? null : BlockPos.of(packed);

        if (wp != null) {
            double dx = wp.getX() + 0.5 - unit.getX();
            double dz = wp.getZ() + 0.5 - unit.getZ();
            if (dx * dx + dz * dz > ARRIVE_SQ && now < sweep.sewv$getInfSweepNext()) {
                unit.getNavigation().moveTo(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5, 1.0);
                return;
            }
        }

        BlockPos next = pickInRect(unit.level(), sweep, unit.getRandom());
        if (next == null) return;
        sweep.sewv$setInfSweepWaypoint(next.asLong());
        sweep.sewv$setInfSweepNext(now + ROTATE_TICKS);
        unit.getNavigation().moveTo(next.getX() + 0.5, next.getY(), next.getZ() + 0.5, 1.0);
    }

    private static BlockPos pickInRect(Level level, ISweepInfantry sweep, RandomSource random) {
        int minX = sweep.sewv$getInfSweepLeft() << 4;
        int maxX = (sweep.sewv$getInfSweepRight() << 4) + 15;
        int minZ = sweep.sewv$getInfSweepTop() << 4;
        int maxZ = (sweep.sewv$getInfSweepBottom() << 4) + 15;
        for (int i = 0; i < PICK_ATTEMPTS; i++) {
            int x = minX + random.nextInt(Math.max(1, maxX - minX + 1));
            int z = minZ + random.nextInt(Math.max(1, maxZ - minZ + 1));
            if (!level.hasChunkAt(x, z)) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.getBlockState(pos.below()).isAir()) return pos;
        }
        return null;
    }
}
