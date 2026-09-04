package com.neoalive.tacz_sewv.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.procedural.events.system.DynamicEvent;
import net.nekoyuni.SimpleEnemyMod.spawn.utils.SpawnHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.notify.HudNotify;
import com.neoalive.tacz_sewv.spawn.AmbientSpawnGate;

/**
 * Scales SEM event spawn distance when {@link SewvConfig#FAR_EVENT_SPAWNS} is on. Lives here because
 * {@code DynamicEvent#getMinDistance}/{@code getMaxDistance} take no level and cannot read config
 * themselves — both the per-player roll ({@code tryEventForPlayer}) and {@code forceEvent} go
 * through the same call.
 *
 * <p>Also notifies the rolled player when an event {@code execute} succeeds — one redirect covers
 * both ambient rolls and {@code /semevent force}.
 */
@Mixin(targets = "net.nekoyuni.SimpleEnemyMod.procedural.events.DynamicEventManager")
public abstract class MixinDynamicEventManager {

    private static final double FAR_SCALE = 2.5;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = false)
    private static void tacz_sewv$gateAmbientEvents(ServerLevel level, CallbackInfo ci) {
        if (!AmbientSpawnGate.allows(level)) {
            ci.cancel();
        }
    }

    @Redirect(
            method = {"tryEventForPlayer", "forceEvent"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/spawn/utils/SpawnHelper;getRandomPositionNearPlayer(Lnet/minecraft/server/level/ServerPlayer;IILnet/minecraft/server/level/ServerLevel;)Lnet/minecraft/core/BlockPos;",
                    remap = false),
            remap = false)
    private static BlockPos tacz_sewv$scaleEventDistance(
            ServerPlayer player, int minDistance, int maxDistance, ServerLevel level) {
        if (SewvConfig.FAR_EVENT_SPAWNS.get()) {
            minDistance = (int) (minDistance * FAR_SCALE);
            maxDistance = (int) (maxDistance * FAR_SCALE);
        }
        return SpawnHelper.getRandomPositionNearPlayer(player, minDistance, maxDistance, level);
    }

    @Redirect(
            method = {"tryEventForPlayer", "forceEvent"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/procedural/events/system/DynamicEvent;execute(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/BlockPos;)Z",
                    remap = false),
            remap = false)
    private static boolean tacz_sewv$notifyOnEventSuccess(
            DynamicEvent event, ServerLevel level, ServerPlayer player, BlockPos pos) {
        boolean ok = event.execute(level, player, pos);
        if (ok) {
            HudNotify.eventNearby(player, event.getId(), pos);
        }
        return ok;
    }
}
