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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.notify.HudNotify;
import com.neoalive.tacz_sewv.spawn.AmbientSpawnGate;
import com.neoalive.tacz_sewv.spawn.SpawnDeny;
import com.neoalive.tacz_sewv.zone.SpawnZones;

/**
 * Scales SEM event spawn distance when {@link SewvConfig#FAR_EVENT_SPAWNS} is on. Lives here because
 * {@code DynamicEvent#getMinDistance}/{@code getMaxDistance} take no level and cannot read config
 * themselves — both the per-player roll ({@code tryEventForPlayer}) and {@code forceEvent} go
 * through the same call.
 *
 * <p>Also notifies the rolled player when an event {@code execute} succeeds — one redirect covers
 * both ambient rolls and {@code /semevent force}.
 *
 * <p>The same redirect applies spawn zones: the spot may move onto a prioritization chunk, and an
 * event whose spot is denied returns false, which keeps its accumulated chance.
 */
@Mixin(targets = "net.nekoyuni.SimpleEnemyMod.procedural.events.DynamicEventManager")
public abstract class MixinDynamicEventManager {

    private static final double FAR_SCALE = 2.5;

    @Inject(method = "forceEvent", at = @At("HEAD"), remap = false)
    private static void tacz_sewv$clearSpawnDeny(ServerLevel level, ServerPlayer player, String eventId,
            CallbackInfoReturnable<Boolean> cir) {
        SpawnDeny.clear();
    }

    /** SEM's own line only says the force failed. This one names the gate that actually refused. */
    @Inject(method = "forceEvent", at = @At("RETURN"), remap = false)
    private static void tacz_sewv$explainSpawnDeny(ServerLevel level, ServerPlayer player, String eventId,
            CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() || player == null || SpawnDeny.unset()) return;
        player.sendSystemMessage(SpawnDeny.component());
        SpawnDeny.clear();
    }

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
        double scale = SewvConfig.FAR_EVENT_SPAWNS.get() ? FAR_SCALE : 1.0;
        double min = event.getMinDistance() * scale;
        double max = event.getMaxDistance() * scale;
        BlockPos rolled = pos;
        pos = SpawnZones.eventSpot(level, player.blockPosition(), pos, min, max, level.random);
        if (pos == null) {
            SpawnDeny.zoneAt(level, rolled);
            return false;
        }
        boolean ok = event.execute(level, player, pos);
        if (ok) {
            HudNotify.eventNearby(player, event.getId(), pos);
        }
        return ok;
    }
}
