package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.bridge.ITowRecovery;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.TowRecoverySupport;

/**
 * Lets an idle RU/US driver claim a nearby friendly hull flagged {@code needs_tow}. Same shape as
 * {@link SeekAbandonedVehicleGoal}: writes an order in evaluation and never runs.
 */
public class SeekTowRecoveryGoal extends Goal {

    private static final int SCAN_INTERVAL = 40;
    private static final int MAX_SCAN_INTERVAL = 200;

    private final AbstractUnit unit;
    private int scanCooldown;
    private int scanInterval = SCAN_INTERVAL;

    public SeekTowRecoveryGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (!shouldScan()) return false;
        if (this.scanCooldown-- > 0) return false;

        VehicleEntity tower = this.unit.getVehicle() instanceof VehicleEntity v ? v : null;
        if (tower == null) return false;

        VehicleEntity victim = TowRecoverySupport.findNearestNeedsTow(this.unit, tower);
        this.scanInterval = victim == null
                ? Math.min(MAX_SCAN_INTERVAL, this.scanInterval * 2)
                : SCAN_INTERVAL;
        this.scanCooldown = this.scanInterval;
        if (victim != null && !victim.getTowedByUUID().isBlank()) return false;
        if (victim != null) {
            if (this.unit instanceof ITowRecovery tow
                    && tow.tacz_sewv$getTowVictimId() == victim.getId()) {
                return false;
            }
            TowRecoverySupport.assignVictim(this.unit, victim.getId());
        }
        return false;
    }

    private boolean shouldScan() {
        if (this.unit.level().isClientSide()) return false;
        if (!SewvConfig.TOW_AUTO_ENABLED.get()) return false;
        if (!(this.unit instanceof RUunitEntity || this.unit instanceof USunitEntity)) return false;
        if (!(this.unit.getVehicle() instanceof VehicleEntity tower)) return false;
        if (tower.getFirstPassenger() != this.unit) return false;
        if (TowRecoverySupport.hasTowOrder(this.unit)) return false;
        if (tower.isTowingAny()) return false;
        if (!TowRecoverySupport.isTowTowerCandidate(tower)) return false;

        if (this.unit instanceof IMortarCrew mortarCrew
                && mortarCrew.sewv$getMortarTargetId() != IMortarCrew.NO_MORTAR) {
            return false;
        }
        if (((IVehicleBoarder) this.unit).tacz_sewv$isBoarding()) return false;
        if (this.unit instanceof ITowRecovery tow && tow.tacz_sewv$isTowingRecovery()) return false;
        return true;
    }
}
