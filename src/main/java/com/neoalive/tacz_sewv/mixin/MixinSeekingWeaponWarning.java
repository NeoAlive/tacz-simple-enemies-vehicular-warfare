package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.network.message.send.SeekingWeaponWarningMessage;
import com.atsuishio.superbwarfare.tools.EntityFindUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.util.ThreatDecoy;

/**
 * Player lock-on / locking tone ({@link SeekingWeaponWarningMessage}) → pop decoy on the
 * warned hull (T-90 smoke, heli/plane flares) in addition to the stock warning sound.
 *
 * <p>The uuid read is a plain {@code getUuid()} call on the message instance, not a mixin
 * {@code @Shadow}: 0.8.9.1 turned the property into a Kotlin {@code SerializedUUID} data-class
 * property whose accessor is a normal public method, and a shadow of it resolves against the
 * mixin's own body rather than the target's (the same trap the vehicle-overlay accessor hit).
 */
@Mixin(value = SeekingWeaponWarningMessage.class, remap = false)
public abstract class MixinSeekingWeaponWarning {

    // NeoForge port: SBW 1.21 handlers are Kotlin `PayloadContext.handler()` extensions, i.e.
    // handler(IPayloadContext) — PayloadContext is a typealias for NeoForge's IPayloadContext.
    @Inject(method = "handler", at = @At("HEAD"))
    private void tacz_sewv$decoyOnLockWarning(IPayloadContext ctx,
            CallbackInfo ci) {
        ServerPlayer sender = ctx.player() instanceof ServerPlayer sp ? sp : null;
        if (sender == null) return;
        SeekingWeaponWarningMessage self = (SeekingWeaponWarningMessage) (Object) this;
        Entity warned = EntityFindUtil.findEntity(sender.level(), self.getUuid().toString());
        ThreatDecoy.popForWarned(warned);
    }
}
