package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.command.quick.MinefieldTracker;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.PmcDownedSupport;

/**
 * A combat engineer working its share of a Deploy Minefield order: run to the next slot faster than
 * a normal walk, kneel for the plant time, put the mine down, go on. Priority 1, MOVE only, so SEM's
 * rifle goal still fires on the way and the chase goal cannot pull it off the field.
 */
public class LayMineGoal extends Goal {

    /** Close enough to plant: within reach of the slot, about one block up or down. */
    private static final double REACH = 1.6;
    private static final long REPATH_TICKS = 10;
    /** A slot not reached in this long is given up on. */
    private static final long SLOT_BUDGET = 240;

    private final PmcUnitEntity unit;
    private long nextPath;

    public LayMineGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide() || !this.unit.isAlive() || this.unit.getVehicle() != null) return false;
        if (PmcDownedSupport.isDowned(this.unit)) return false;
        return MinefieldTracker.legFor(this.unit) != null;
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
        this.nextPath = Long.MIN_VALUE;
    }

    @Override
    public void stop() {
        this.unit.getNavigation().stop();
        this.unit.setSprinting(false);
    }

    @Override
    public void tick() {
        if (!(this.unit.level() instanceof ServerLevel level)) return;
        MinefieldTracker.Leg leg = MinefieldTracker.legFor(this.unit);
        if (leg == null) return;
        MinefieldTracker.Planned slot = leg.current();
        if (slot == null) return;
        long now = level.getGameTime();
        Vec3 at = slot.standAt();
        double dx = at.x - this.unit.getX();
        double dz = at.z - this.unit.getZ();
        boolean there = dx * dx + dz * dz <= REACH * REACH && Math.abs(at.y - this.unit.getY()) <= 1.5;

        if (!there) {
            if (MinefieldTracker.slotExpired(leg, now, SLOT_BUDGET)) {
                MinefieldTracker.skip(leg);
                return;
            }
            this.unit.setSprinting(true);
            if (now >= this.nextPath || this.unit.getNavigation().isDone()) {
                this.nextPath = now + REPATH_TICKS;
                this.unit.getNavigation().moveTo(at.x, at.y, at.z, SewvConfig.MINEFIELD_MOVE_SPEED.get());
            }
            return;
        }
        this.unit.getNavigation().stop();
        this.unit.setSprinting(false);
        this.unit.getLookControl().setLookAt(at.x, at.y, at.z);
        MinefieldTracker.startPlant(leg, now, SewvConfig.MINEFIELD_PLANT_TICKS.get());
        if (now >= leg.plantAt()) {
            MinefieldTracker.plant(level, this.unit, leg);
            this.nextPath = Long.MIN_VALUE;
        }
    }
}
