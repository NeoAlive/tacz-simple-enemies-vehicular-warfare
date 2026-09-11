package com.neoalive.tacz_sewv.mixin;

import java.util.UUID;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;

/**
 * Hull-event voicelines: rising-edge low health, and MG/cannon fire callouts on actual AI shots.
 */
@Mixin(VehicleEntity.class)
public abstract class MixinVehicleVoicelines {

    @Inject(method = "onHurt", at = @At("TAIL"), remap = false)
    private void tacz_sewv$lowHealthVoice(float amount, Entity attacker, boolean bl, CallbackInfo ci) {
        CrewRadio.maybeLowHealth((VehicleEntity) (Object) this);
    }

    /**
     * Fire callout on a real launch. Hooks VehicleEntity's shoot so cancelled HEAD gates never
     * announce, and so native SBW AI fire (not only tryAiFireAssist) is covered. Player shooters
     * are ignored.
     */
    @Inject(
            method = "vehicleShoot(Lnet/minecraft/world/entity/LivingEntity;Ljava/util/UUID;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("TAIL"), remap = false)
    private void tacz_sewv$shootVoice(LivingEntity shooter, UUID uuid, Vec3 targetPos, CallbackInfo ci) {
        if (!(shooter instanceof AbstractUnit unit)) return;
        VehicleEntity hull = (VehicleEntity) (Object) this;
        int seat = hull.getSeatIndex(unit);
        if (seat < 0) return;
        int role = VehicleWeapons.roleOfCurrentWeapon(hull, seat);
        if (role == VehicleWeapons.WEAPON_MG || role == VehicleWeapons.WEAPON_CANNON) {
            CrewRadio.playShoot(hull, role);
        }
    }
}
