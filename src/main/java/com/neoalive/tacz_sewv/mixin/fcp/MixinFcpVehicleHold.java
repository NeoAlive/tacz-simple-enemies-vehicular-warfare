package com.neoalive.tacz_sewv.mixin.fcp;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.crew.VehicleAccess.Verdict;
import com.neoalive.tacz_sewv.crew.VehicleAccessLive;
import com.neoalive.tacz_sewv.util.VehicleDrops;

/**
 * FCP 2.0's two hull bases keep ammo in their own hold, spill it in their remove override before
 * SBW's, and open it without going through SBW's openMenu. Both need our gates in front.
 */
@Mixin(targets = {
        "frontline.combat.fcp.entity.vehicle.CamoVehicleBase",
        "frontline.combat.fcp.entity.vehicle.CamoArtilleryBase"
})
public abstract class MixinFcpVehicleHold {

    // remove is Entity's vanilla method, SRG in production, so remap stays on.
    @Inject(method = "remove", at = @At("HEAD"), remap = true)
    private void tacz_sewv$scrubHold(Entity.RemovalReason reason, CallbackInfo ci) {
        VehicleDrops.scrubFcpHold((VehicleEntity) (Object) this, reason);
    }

    @Inject(method = "openVehicleInventory", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$lockHold(ServerPlayer player, CallbackInfo ci) {
        Verdict verdict = VehicleAccessLive.verdict(player, (VehicleEntity) (Object) this);
        if (verdict == Verdict.ALLOW) return;
        VehicleAccessLive.tell(player, verdict);
        ci.cancel();
    }
}
