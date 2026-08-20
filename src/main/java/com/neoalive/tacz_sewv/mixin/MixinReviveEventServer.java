package com.neoalive.tacz_sewv.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import team.creative.playerrevive.PlayerRevive;
import team.creative.playerrevive.server.ReviveEventServer;

import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Forces PlayerReviveMod's bleed-out state to work in an un-published singleplayer world too.
 *
 * <p>PlayerReviveMod's own gate ({@code ReviveEventServer.isReviveActive}) is
 * {@code CONFIG.bleedInSingleplayer || server.isPublished()} — so on a plain singleplayer world
 * (not opened to LAN) a player who would go down instead dies outright, and PMC auto-revive
 * ({@code PlayerReviveGoal}) never has anything to do there. Rather than requiring every player to
 * hand-edit PlayerReviveMod's own config file, this reproduces {@code bleedInSingleplayer=true} at
 * the injection site, gated by {@link SewvConfig#PMC_REVIVE_FORCE_SINGLEPLAYER} — only ever applied
 * ({@code PlayerReviveMixinPlugin}) when PlayerReviveMod is actually present.
 *
 * <p>The creative-mode exclusion is reproduced rather than skipped, so a creative player is left
 * exactly as PlayerReviveMod itself would leave them (immune unless {@code bleeding.triggerForCreative}).
 */
@Mixin(value = ReviveEventServer.class, remap = false)
public class MixinReviveEventServer {

    @Inject(method = "isReviveActive", at = @At("HEAD"), cancellable = true, remap = false)
    private static void tacz_sewv$forceSingleplayerRevive(Entity player, CallbackInfoReturnable<Boolean> cir) {
        if (!SewvConfig.PMC_REVIVE_FORCE_SINGLEPLAYER.get()) return;
        if (player instanceof Player p && p.isCreative() && !PlayerRevive.CONFIG.bleeding.triggerForCreative) {
            cir.setReturnValue(false);
        } else {
            cir.setReturnValue(true);
        }
    }
}
