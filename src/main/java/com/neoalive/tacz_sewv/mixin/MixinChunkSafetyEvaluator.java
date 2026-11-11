package com.neoalive.tacz_sewv.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.zone.SpawnZones;
import com.neoalive.tacz_sewv.zone.ZoneType;

/**
 * A no-zone chunk reads as a player base to SEM, so its events refuse the spot before any block
 * scan. This also covers the cave event, which finds its own spot and ignores the rolled one.
 * Only NOZONE answers here: the prioritization-only rule runs later, in the execute redirect,
 * where a spot can still be moved.
 */
@Mixin(targets = "net.nekoyuni.SimpleEnemyMod.procedural.events.system.ChunkSafetyEvaluator")
public abstract class MixinChunkSafetyEvaluator {

    @Inject(method = "isPlayerBase", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void tacz_sewv$noZoneIsBase(ServerLevel level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (SpawnZones.typeAt(level, pos) == ZoneType.NOZONE) cir.setReturnValue(true);
    }
}
