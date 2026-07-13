package com.neoalive.tacz_sewv.mixin;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.entity.ai.VehicleAiGoals;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// RUunitEntity extends AbstractUnit directly (a sibling of PmcUnitEntity, not a
// subclass), so it needs its own setupRoleGoals() injection to get vehicle AI.
// IHelicopterPilot's default methods (persistent-NBT-backed) supply the flight
// state DriveHelicopterGoal's takeoff/land state machine works with; TankSpawner
// issues the takeoff on spawn and these hostile crews take no player flight orders.
@Mixin(RUunitEntity.class)
public abstract class MixinRUunitEntity implements IHelicopterPilot {

    @Inject(method = "setupRoleGoals", at = @At("TAIL"), remap = false)
    private void tacz_sewv$addVehicleGoals(CallbackInfo ci) {
        VehicleAiGoals.addDriveGoals((AbstractUnit) (Object) this);
    }
}
