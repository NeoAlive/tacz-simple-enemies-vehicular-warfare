package com.neoalive.tacz_sewv.mixin;

import java.util.UUID;
import java.util.function.Supplier;

import com.atsuishio.superbwarfare.network.message.send.SeekingWeaponWarningMessage;
import com.atsuishio.superbwarfare.tools.EntityFindUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.util.ThreatDecoy;

/**
 * Player lock-on / locking tone ({@link SeekingWeaponWarningMessage}) → pop decoy on the
 * warned hull (T-90 smoke, heli/plane flares) in addition to the stock warning sound.
 */
@Mixin(value = SeekingWeaponWarningMessage.class, remap = false)
public abstract class MixinSeekingWeaponWarning {

    @Shadow
    public abstract UUID getUuid();

    @Inject(method = "handler", at = @At("HEAD"))
    private void tacz_sewv$decoyOnLockWarning(Supplier<NetworkEvent.Context> ctxSupplier,
            CallbackInfo ci) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer sender = ctx.getSender();
        if (sender == null) return;
        Entity warned = EntityFindUtil.findEntity(sender.level(), this.getUuid().toString());
        ThreatDecoy.popForWarned(warned);
    }
}
