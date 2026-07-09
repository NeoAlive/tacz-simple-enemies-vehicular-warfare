package com.neoalive.tacz_sewv.mixin;

import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import com.neoalive.tacz_sewv.entity.ai.BoardVehicleGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PmcUnitEntity.class)
public abstract class MixinPmcUnitEntity {

    @Inject(method = "setupRoleGoals", at = @At("TAIL"), remap = false)
    private void tacz_sewv$addVehicleGoals(CallbackInfo ci) {
        PmcUnitEntity self = (PmcUnitEntity) (Object) this;
        self.goalSelector.addGoal(3, new BoardVehicleGoal(self));
    }
}

public interface IVehicleBoarder {
    void tacz_sewv$setMountTargetId(int id);
    int tacz_sewv$getMountTargetId();
    boolean tacz_sewv$isBoarding();
    void tacz_sewv$setBoarding(boolean b);
}