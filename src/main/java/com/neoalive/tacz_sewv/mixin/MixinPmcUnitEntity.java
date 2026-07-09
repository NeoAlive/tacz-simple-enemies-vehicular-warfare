package com.neoalive.tacz_sewv.mixin;

import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.entity.ai.BoardVehicleGoal;
import net.minecraft.world.entity.Mob;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.neoalive.tacz_sewv.network.NetworkHandler;

@Mixin(PmcUnitEntity.class)
public abstract class MixinPmcUnitEntity implements IVehicleBoarder {

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
    System.out.println("[TACZ_SEWV] ===== INJECT FIRED =====");
    PmcUnitEntity self = (PmcUnitEntity) (Object) this;
    ((Mob) self).goalSelector.addGoal(0, new BoardVehicleGoal(self));
    System.out.println("[TACZ_SEWV] Goal added, selector size now: " + ((Mob) self).goalSelector.getAvailableGoals().size());
}
}