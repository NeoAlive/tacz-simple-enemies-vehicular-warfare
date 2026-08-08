package com.neoalive.tacz_sewv.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.spawn.utils.SpawnHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.neoalive.tacz_sewv.init.ModGameRules;

/**
 * Scales SEM event spawn distance when {@code sewvFarEventSpawns} is on. Lives here because
 * {@code DynamicEvent#getMinDistance}/{@code getMaxDistance} take no level and cannot read a
 * gamerule themselves — both the per-player roll ({@code tryEventForPlayer}) and
 * {@code forceEvent} go through the same call.
 */
@Mixin(targets = "net.nekoyuni.SimpleEnemyMod.procedural.events.DynamicEventManager")
public abstract class MixinDynamicEventManager {

    private static final double FAR_SCALE = 2.5;

    @Redirect(
            method = {"tryEventForPlayer", "forceEvent"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/spawn/utils/SpawnHelper;getRandomPositionNearPlayer(Lnet/minecraft/server/level/ServerPlayer;IILnet/minecraft/server/level/ServerLevel;)Lnet/minecraft/core/BlockPos;",
                    remap = false),
            remap = false)
    private static BlockPos tacz_sewv$scaleEventDistance(
            ServerPlayer player, int minDistance, int maxDistance, ServerLevel level) {
        if (level.getGameRules().getBoolean(ModGameRules.FAR_EVENT_SPAWNS)) {
            minDistance = (int) (minDistance * FAR_SCALE);
            maxDistance = (int) (maxDistance * FAR_SCALE);
        }
        return SpawnHelper.getRandomPositionNearPlayer(player, minDistance, maxDistance, level);
    }
}
