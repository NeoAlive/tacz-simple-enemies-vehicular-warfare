package com.neoalive.tacz_sewv.mixin;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.TankSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Target the concrete event class
@Mixin(targets = "net.nekoyuni.SimpleEnemyMod.procedural.events.type.CombatEvent")
public abstract class MixinCombatEvent {

    @Inject(method = "execute", at = @At("TAIL"), remap = false)
    private void tacz_sewv$maybeSpawnTanks(
            ServerLevel level, ServerPlayer player, BlockPos centerPos,
            CallbackInfoReturnable<Boolean> cir) {

        // Config gate
        if (!SewvConfig.TANKS_IN_EVENTS.get()) return;

        // Only proceed if the event actually succeeded (returned true)
        if (cir.getReturnValue() == null || !cir.getReturnValue()) return;

        int separation = 24;

        // Roll for RU tank (extremely low chance)
        if (level.random.nextDouble() < SewvConfig.TANK_SPAWN_CHANCE.get()) {
            BlockPos posRu = TankSpawner.adjustHeight(level, centerPos.offset(separation, 0, 0));
            TankSpawner.spawnTankWithDriver(level, posRu, true);
        }

        // Roll for US tank (independent roll)
        if (level.random.nextDouble() < SewvConfig.TANK_SPAWN_CHANCE.get()) {
            BlockPos posUs = TankSpawner.adjustHeight(level, centerPos.offset(-separation, 0, 0));
            TankSpawner.spawnTankWithDriver(level, posUs, false);
        }
    }
}
