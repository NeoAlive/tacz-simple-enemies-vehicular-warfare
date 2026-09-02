package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.fob.FobResupplySupport;

/**
 * Holds an assigned PMC (and its hull) still at the stockpile until eligible ammo is topped up.
 * On-foot units path toward the stockpile pad when they need ammo and the stockpile has stock.
 */
public class FobResupplyGoal extends Goal {

    private static final int REPATH_INTERVAL = 20;

    private final PmcUnitEntity unit;
    @Nullable
    private BlockPos destination;
    private int repathCooldown;

    public FobResupplyGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        VehicleEntity hull = this.unit.getVehicle() instanceof VehicleEntity v ? v : null;
        if (FobResupplySupport.shouldResupply(this.unit, hull)) return true;
        this.destination = FobResupplySupport.resupplyDestination(this.unit, hull);
        return this.destination != null && hull == null && !this.unit.isPassenger();
    }

    @Override
    public boolean canContinueToUse() {
        if (this.unit.level().isClientSide()) return false;
        VehicleEntity hull = this.unit.getVehicle() instanceof VehicleEntity v ? v : null;
        if (FobResupplySupport.shouldResupply(this.unit, hull)) return true;
        if (hull != null || this.unit.isPassenger()) return false;
        // Re-resolve rather than trusting what canUse cached. This goal holds MOVE, and a stale
        // destination kept holding it after the need had passed — or after the player gave a MOVE
        // order, which resupply now stands aside for.
        this.destination = FobResupplySupport.resupplyDestination(this.unit, null);
        return this.destination != null;
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
        VehicleEntity hull = this.unit.getVehicle() instanceof VehicleEntity v ? v : null;
        if (FobResupplySupport.shouldResupply(this.unit, hull)) {
            this.unit.getNavigation().stop();
            FobResupplySupport.tickResupply(this.unit, hull);
            return;
        }

        if (this.destination == null) return;
        if (--this.repathCooldown <= 0) {
            this.repathCooldown = REPATH_INTERVAL;
            this.unit.getNavigation().moveTo(
                    this.destination.getX() + 0.5,
                    this.destination.getY(),
                    this.destination.getZ() + 0.5,
                    1.0);
        }
    }
}
