package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.network.message.send.SetVehicleSkinMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.skin.VehicleSkinSupport;

/**
 * Spray-GUI: claim only sewv catalog ids ({@code ru}, {@code ru_0}, …). Anything else is left
 * to SBW so datapack skins from other mods keep working.
 */
@Mixin(value = SetVehicleSkinMessage.class, remap = false)
public abstract class MixinSetVehicleSkinMessage {

    @Shadow
    public abstract int getEntityId();

    @Shadow
    public abstract String getSkinId();

    // NeoForge port: SBW 1.21 handlers are Kotlin `PayloadContext.handler()` extensions, i.e.
    // handler(IPayloadContext) — PayloadContext is a typealias for NeoForge's IPayloadContext.
    @Inject(method = "handler", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$applySewvSkin(IPayloadContext ctx, CallbackInfo ci) {
        String skinId = this.getSkinId();
        if (!VehicleSkinSupport.isSewvSkinId(skinId)) {
            // Datapack / blank pick — drop sticky paint so it cannot override the foreign skin.
            ServerPlayer sender = ctx.player() instanceof ServerPlayer sp ? sp : null;
            if (sender != null) {
                Entity target = sender.level().getEntity(this.getEntityId());
                if (target instanceof VehicleEntity vehicle) {
                    VehicleSkinSupport.clearSticky(vehicle);
                }
            }
            return;
        }

        ServerPlayer sender = ctx.player() instanceof ServerPlayer sp ? sp : null;
        if (sender == null) {
            ci.cancel();
            return;
        }
        Entity target = sender.level().getEntity(this.getEntityId());
        if (!(target instanceof VehicleEntity vehicle)) {
            ci.cancel();
            return;
        }
        VehicleSkinSupport.setFromSkinId(vehicle, skinId);
        ci.cancel();
    }
}
