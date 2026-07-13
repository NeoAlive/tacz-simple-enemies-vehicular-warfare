package com.neoalive.tacz_sewv.mixin;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.entity.ai.BoardVehicleGoal;
import com.neoalive.tacz_sewv.entity.ai.VehicleAiGoals;
import net.minecraft.world.entity.Mob;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// IHelicopterPilot needs no method bodies here — its default methods store the
// flight state in the entity's persistent NBT (so it survives world reloads).
// The boarding order below is deliberately transient: it targets an entity by
// network id, which is not stable across sessions, so persisting it would be
// wrong — a pending board order is simply dropped on reload.
@Mixin(PmcUnitEntity.class)
public abstract class MixinPmcUnitEntity implements IVehicleBoarder, IHelicopterPilot {

    @Unique
    private int tacz_sewv$mountTargetId = -1;

    @Unique
    private boolean tacz_sewv$boarding = false;

    @Override
    public void tacz_sewv$setMountTargetId(int id) {
        this.tacz_sewv$mountTargetId = id;
    }

    @Override
    public int tacz_sewv$getMountTargetId() {
        return this.tacz_sewv$mountTargetId;
    }

    @Override
    public void tacz_sewv$setBoarding(boolean boarding) {
        this.tacz_sewv$boarding = boarding;
    }

    @Override
    public boolean tacz_sewv$isBoarding() {
        return this.tacz_sewv$boarding;
    }

    @Inject(method = "setupRoleGoals", at = @At("TAIL"), remap = false)
    private void tacz_sewv$addVehicleGoals(CallbackInfo ci) {
        PmcUnitEntity self = (PmcUnitEntity) (Object) this;
        ((Mob) self).goalSelector.addGoal(1, new BoardVehicleGoal(self));
        VehicleAiGoals.addDriveGoals(self);
    }
}
