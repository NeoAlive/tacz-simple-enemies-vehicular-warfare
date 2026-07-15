package com.neoalive.tacz_sewv.mixin.client;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.client.system.ClientGlowManager;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * SEM stores its selection glow as entity ids.  A driver is normally hidden inside
 * the hull, so use its vehicle id instead: selecting the driver then visibly
 * selects the vehicle.
 */
@Mixin(ClientGlowManager.class)
public abstract class MixinClientGlowManager {

    /** Source driver id to the vehicle id that was made to glow for it. */
    private static final Map<Integer, Integer> tacz_sewv$driverGlowTargets = new HashMap<>();

    @ModifyVariable(method = "addEntity", at = @At("HEAD"), argsOnly = true, remap = false)
    private static int tacz_sewv$glowVehicleForSelectedDriver(int entityId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return entityId;

        Entity entity = minecraft.level.getEntity(entityId);
        if (entity instanceof PmcUnitEntity pmc
                && pmc.getVehicle() instanceof VehicleEntity vehicle
                && vehicle.getFirstPassenger() == pmc) {
            int vehicleId = vehicle.getId();
            tacz_sewv$driverGlowTargets.put(entityId, vehicleId);
            return vehicleId;
        }

        return entityId;
    }

    @ModifyVariable(method = "removeEntity", at = @At("HEAD"), argsOnly = true, remap = false)
    private static int tacz_sewv$removeVehicleGlowForDriver(int entityId) {
        Integer vehicleId = tacz_sewv$driverGlowTargets.remove(entityId);
        return vehicleId != null ? vehicleId : entityId;
    }

    @Inject(method = "clear", at = @At("HEAD"), remap = false)
    private static void tacz_sewv$clearDriverGlowTargets(CallbackInfo ci) {
        tacz_sewv$driverGlowTargets.clear();
    }
}
