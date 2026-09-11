package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.entity.ai.support.TowSupport;

/**
 * Keeps the TOW / FCP emplacement a unit is riding loaded.
 *
 * <p>SBW's per-seat fire loop already aims and shoots; FCP magazine emplacements only auto-reload
 * for a Player, and ZiS-3 / FCP TOW / Kornet only load via player right-click. This goal fills
 * that gap for SEM crews — see {@link TowSupport#reload}.
 */
public class ManTowGoal extends Goal {

    private final AbstractUnit unit;
    private VehicleEntity weapon;

    public ManTowGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (!TowSupport.needsAiReload(this.unit)) return false;
        if (!(this.unit.getVehicle() instanceof VehicleEntity hull)) return false;
        this.weapon = hull;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.weapon != null
                && this.unit.getVehicle() == this.weapon
                && !this.weapon.isWreck();
    }

    @Override
    public void tick() {
        TowSupport.reload(this.weapon, this.unit);
    }

    @Override
    public void stop() {
        this.weapon = null;
    }
}
