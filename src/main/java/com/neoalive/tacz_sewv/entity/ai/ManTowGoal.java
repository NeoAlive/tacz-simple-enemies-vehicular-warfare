package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.TowEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.EnumSet;

/**
 * Keeps the TOW a unit is riding loaded.
 *
 * <p>That is the entire goal, because it is the only thing missing. A TOW is a normal
 * crewed vehicle: the board key already puts a unit in its seat, SBW's own per-seat loop
 * in {@code VehicleEntity.tick} already looks at the target and pulls the trigger, and
 * {@link VehicleTargetScanGoal} already finds it something to shoot. What no part of SBW
 * ever does for a mob is put the next missile on the rail — see {@link TowSupport#reload}.
 *
 * <p>Loading is unconditional on having a target: a launcher sits ready, it doesn't wait
 * for someone to appear before loading. So this reloads the moment the launcher will take
 * one, which is also what keeps the ~7.5 s cycle tight enough to matter in a fight.
 */
public class ManTowGoal extends Goal {

    private final AbstractUnit unit;
    private TowEntity tow;

    public ManTowGoal(AbstractUnit unit) {
        this.unit = unit;
        // Claims nothing. Loading is not moving, looking or targeting, and the crew is
        // sitting in a seat while it happens — this must never contend with the goals
        // that are actually fighting the launcher.
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (!(this.unit.getVehicle() instanceof TowEntity t)) return false;
        this.tow = t;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.tow != null
                && this.unit.getVehicle() == this.tow
                && !this.tow.isWreck();
    }

    @Override
    public void tick() {
        TowSupport.reload(this.tow, this.unit);
    }

    @Override
    public void stop() {
        this.tow = null;
    }
}
