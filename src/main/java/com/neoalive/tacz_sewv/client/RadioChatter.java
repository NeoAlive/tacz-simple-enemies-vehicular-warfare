package com.neoalive.tacz_sewv.client;

import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.item.HandheldRadioItem;
import com.neoalive.tacz_sewv.item.RadioSettings;

/**
 * Ambient radio traffic for the local player while they carry a radio with chatter on. Purely
 * cosmetic and client-only: nothing is sent or synced. Counts unpaused client ticks, so a paused
 * game neither plays nor brings the next transmission closer. The inventory is checked once a second,
 * and a transmission is cut when the radio goes away or chatter is turned off.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class RadioChatter {

    private static final int GAP_MIN = 60 * 20;
    private static final int GAP_MAX = 240 * 20;
    private static final double ENEMY_CHANCE = 0.3;
    private static final float VOLUME = 0.45F;
    private static final int CHECK_TICKS = 20;

    private static long now;
    private static long nextAt = -1;
    private static SoundInstance playing;
    private static boolean listening;

    private RadioChatter() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            nextAt = -1;
            listening = false;
            stop(mc);
            return;
        }
        if (mc.isPaused()) return;
        now++;
        if (now % CHECK_TICKS == 0) {
            listening = ClientConfig.flag(ClientConfig.RADIO_CHATTER)
                    && !HandheldRadioItem.findCarried(mc.player, s -> RadioSettings.readComms(s).chatter()).isEmpty();
        }
        if (!listening) {
            nextAt = -1;
            stop(mc);
            return;
        }
        if (nextAt < 0) {
            nextAt = now + gap();
            return;
        }
        if (now < nextAt) return;
        if ((playing != null && mc.getSoundManager().isActive(playing)) || RadioVoiceHud.receiving()) {
            nextAt = now + 40;
            return;
        }
        ModSounds.SoundPool pool = ThreadLocalRandom.current().nextDouble() < ENEMY_CHANCE
                ? ModSounds.chatter("ru") : ModSounds.chatter("en");
        if (pool == null) pool = ModSounds.chatter("en");
        if (pool == null) {
            nextAt = now + gap();
            return;
        }
        int index = pool.draw();
        playing = SimpleSoundInstance.forUI(pool.event(index), 1.0F, VOLUME);
        mc.getSoundManager().play(playing);
        nextAt = now + pool.ticks(index) + gap();
    }

    private static void stop(Minecraft mc) {
        if (playing == null) return;
        mc.getSoundManager().stop(playing);
        playing = null;
    }

    private static int gap() {
        return ThreadLocalRandom.current().nextInt(GAP_MIN, GAP_MAX + 1);
    }
}
