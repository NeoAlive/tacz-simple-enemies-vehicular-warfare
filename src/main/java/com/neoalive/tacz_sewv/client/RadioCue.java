package com.neoalive.tacz_sewv.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;

import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.init.ModSounds;

/** Handheld radio cues heard only by the local player: notification clicks and voice key-up/key-down. */
public final class RadioCue {

    private RadioCue() {}

    public static void notification(boolean urgent) {
        if (!ClientConfig.flag(ClientConfig.RADIO_NOTIFICATION_SOUND)) return;
        play(urgent ? ModSounds.RADIO_SQUELCH.next() : ModSounds.RADIO_CLICK.next(), 1.0F);
    }

    public static void click(float volume) {
        play(ModSounds.RADIO_CLICK.next(), volume);
    }

    public static void squelch(float volume) {
        play(ModSounds.RADIO_SQUELCH.next(), volume);
    }

    private static void play(SoundEvent sound, float volume) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, 1.0F, volume));
    }
}
