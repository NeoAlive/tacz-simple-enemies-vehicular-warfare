package com.neoalive.tacz_sewv.entity.ai.sensor;

import com.tacz.guns.api.event.common.GunFireEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Event-driven awareness cue producers that do not go through {@code Level.playSound}.
 *
 * <p>TaCZ gunfire is packet-only ({@code SoundManager.sendSoundToNearby}); {@link GunFireEvent}
 * is the cheap server seam — one call per shot, then registry dedupe (6 s / 16-block cell) absorbs
 * full-auto spam.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AwarenessCueEvents {

    private AwarenessCueEvents() {}

    @SubscribeEvent
    public static void onGunFire(GunFireEvent event) {
        if (event.getLogicalSide() != LogicalSide.SERVER) return;
        if (!SewvConfig.SPEC.isLoaded() || !SewvConfig.AWARENESS_CUES_ENABLED.get()) return;

        LivingEntity shooter = event.getShooter();
        if (shooter == null || !shooter.isAlive()) return;
        if (!(shooter.level() instanceof ServerLevel level)) return;

        AwarenessCues.registerTaczFire(level, shooter);
    }
}
