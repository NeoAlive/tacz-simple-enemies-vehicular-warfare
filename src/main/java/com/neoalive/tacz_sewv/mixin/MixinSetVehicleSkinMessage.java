package com.neoalive.tacz_sewv.mixin;

import java.util.Locale;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.network.message.send.SetVehicleSkinMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.skin.VehicleSkinSupport;

/**
 * Spray-GUI selection writes sewv sticky paint (and the matching {@code skinId} for the GUI
 * highlight) instead of SBW's datapack skin id.
 */
@Mixin(value = SetVehicleSkinMessage.class, remap = false)
public abstract class MixinSetVehicleSkinMessage {

    @Shadow
    public abstract int getEntityId();

    @Shadow
    public abstract String getSkinId();

    @Inject(method = "handler", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$applySewvSkin(Supplier<NetworkEvent.Context> ctxSupplier, CallbackInfo ci) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer sender = ctx.getSender();
        if (sender == null) {
            ci.cancel();
            return;
        }
        Entity target = sender.level().getEntity(this.getEntityId());
        if (!(target instanceof VehicleEntity vehicle)) {
            ci.cancel();
            return;
        }
        CrewFacts.Faction faction = parse(this.getSkinId());
        VehicleSkinSupport.set(vehicle, faction);
        ci.cancel();
    }

    @Unique
    @Nullable
    private static CrewFacts.Faction parse(@Nullable String skinId) {
        if (skinId == null || skinId.isBlank()) {
            return null;
        }
        return switch (skinId.toLowerCase(Locale.ROOT)) {
            case "ru" -> CrewFacts.Faction.RU;
            case "us" -> CrewFacts.Faction.US;
            case "pmc" -> CrewFacts.Faction.PMC;
            default -> null;
        };
    }
}
