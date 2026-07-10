package com.neoalive.tacz_sewv.mixin;

import com.neoalive.tacz_sewv.entity.ai.VehicleAiGoals;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// USunitEntity extends AbstractUnit directly (a sibling of PmcUnitEntity, not a
// subclass), so it needs its own setupRoleGoals() injection to get vehicle AI.
@Mixin(USunitEntity.class)
public abstract class MixinUSunitEntity {

    @Inject(method = "setupRoleGoals", at = @At("TAIL"), remap = false)
    private void tacz_sewv$addVehicleGoals(CallbackInfo ci) {
        VehicleAiGoals.addDriveGoals((AbstractUnit) (Object) this);
    }
}
