package com.neoalive.tacz_sewv.mixin;

import java.util.UUID;

import com.atsuishio.superbwarfare.data.drone_attachment.DroneAttachmentData;
import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.support.DroneControl;
import com.neoalive.tacz_sewv.entity.ai.support.DroneSupport;

/**
 * AI kamikaze drones: skip warhead unless dive-armed; never entity-crash the owner/friendlies
 * (or anyone during spawn grace).
 */
@Mixin(value = DroneEntity.class, remap = false)
public abstract class MixinDroneKamikaze {

    @Redirect(
            method = "destroy",
            at = @At(
                    value = "FIELD",
                    target = "Lcom/atsuishio/superbwarfare/data/drone_attachment/DroneAttachmentData;isKamikaze:Z"
            )
    )
    private boolean tacz_sewv$gateKamikaze(DroneAttachmentData data) {
        DroneEntity self = (DroneEntity) (Object) this;
        if (DroneControl.isAiOwned(self) && !DroneControl.isDiveArmed(self)) {
            return false;
        }
        return data.isKamikaze;
    }

    @Inject(method = "hitEntityCrash", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$ignoreOwnerCrash(Player player, Entity target, CallbackInfo ci) {
        DroneEntity self = (DroneEntity) (Object) this;
        if (!DroneControl.isAiOwned(self)) return;
        if (DroneControl.inSpawnGrace(self)) {
            ci.cancel();
            return;
        }
        UUID ownerId = DroneControl.readOwnerId(self);
        if (ownerId != null && ownerId.equals(target.getUUID())) {
            ci.cancel();
            return;
        }
        AbstractUnit crew = DroneSupport.crewOf(self);
        if (crew == null) return;
        if (target instanceof LivingEntity living) {
            if (VehicleTargeting.isNonHostile(crew, living)) {
                ci.cancel();
            }
            return;
        }
        // Friendly-crewed hulls (no hostile passenger) must not detonate on bump.
        if (target instanceof VehicleEntity hull && !DroneSupport.hasHostilePassenger(crew, hull)) {
            ci.cancel();
        }
    }
}
