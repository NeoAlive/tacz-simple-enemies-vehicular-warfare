package com.neoalive.tacz_sewv.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.procedural.events.system.DynamicEvent;
import net.nekoyuni.SimpleEnemyMod.world.data.EventProbabilityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.notify.HudNotify;
import com.neoalive.tacz_sewv.procedural.events.EventBand;
import com.neoalive.tacz_sewv.procedural.events.EventSpots;
import com.neoalive.tacz_sewv.procedural.marks.EventMarkEngine;
import com.neoalive.tacz_sewv.procedural.marks.MarkEvents;
import com.neoalive.tacz_sewv.spawn.AmbientSpawnGate;
import com.neoalive.tacz_sewv.spawn.SpawnDeny;
import com.neoalive.tacz_sewv.zone.SpawnZones;

/**
 * The legacy event engine: SEM's own roller, with each event's ring scaled 2.5x and clamped to the
 * simulated radius ({@link EventSpots}). While {@link EventMarkEngine} runs, the events it owns are
 * skipped here and only the anchored ones (cave, base shelling) still roll. Lives here because
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
    private static final String CAVE_EVENT = "cave_extraction";
    private static final String NAVAL_EVENT = "naval_battle";

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

    /** Events the mark engine owns do not roll here while it runs. {@code forceEvent} is unaffected. */
    @Inject(method = "tryEventForPlayer", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void tacz_sewv$leaveToMarks(ServerLevel level, EventProbabilityData data, DynamicEvent event,
            ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (MarkEvents.marked(event.getId()) && EventMarkEngine.running(level)) {
            cir.setReturnValue(false);
        }
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
        // Only feeds SEM's biome weighting now; the real spot is picked in the execute redirect.
        return EventSpots.rough(level, player, tacz_sewv$band(level, minDistance, maxDistance), level.random);
    }

    private static EventBand tacz_sewv$band(ServerLevel level, double min, double max) {
        return tacz_sewv$band(level, min, max, 0);
    }

    private static EventBand tacz_sewv$band(ServerLevel level, double min, double max, int reach) {
        return EventSpots.band(level, (int) (min * FAR_SCALE), (int) (max * FAR_SCALE), reach);
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
        EventBand band = tacz_sewv$band(level, event.getMinDistance(), event.getMaxDistance(),
                MarkEvents.reach(event.getId()));
        // The cave event finds its own cave near the player and ignores the spot.
        if (!CAVE_EVENT.equals(event.getId())) {
            pos = EventSpots.pick(level, player, band, NAVAL_EVENT.equals(event.getId()), level.random);
            if (pos == null) return false;
        }
        BlockPos rolled = pos;
        pos = SpawnZones.eventSpot(level, player.blockPosition(), pos, band.min(), band.max(), level.random);
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
