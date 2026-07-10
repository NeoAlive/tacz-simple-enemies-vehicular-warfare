package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleInteractLock {

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$blockEnemyVehicleInteract(
            Player player, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir) {

        VehicleEntity self = (VehicleEntity) (Object) this;

        // If any passenger is an enemy RU/US unit, deny interaction entirely
        for (Entity passenger : self.getPassengers()) {
            if (passenger instanceof RUunitEntity || passenger instanceof USunitEntity) {
                cir.setReturnValue(InteractionResult.FAIL); // enemy vehicle — hands off
                return;
            }
        }
        // No enemy crew → let SW's normal interact run (fall through, no cancel)
    }
}