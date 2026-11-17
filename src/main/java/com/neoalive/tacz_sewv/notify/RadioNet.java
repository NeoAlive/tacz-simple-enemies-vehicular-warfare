package com.neoalive.tacz_sewv.notify;

import net.minecraft.server.level.ServerPlayer;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.item.HandheldRadioItem;
import com.neoalive.tacz_sewv.item.RadioSettings;

/**
 * Who hears the radio net. A toast reaches a player only while they carry a handheld radio with
 * notifications on. Creative players are not exempt: unlike calling a fire mission, this is not a
 * test convenience but what the radio is for.
 */
public final class RadioNet {

    private RadioNet() {}

    public static boolean receives(ServerPlayer player) {
        if (!requireRadio()) return true;
        return !HandheldRadioItem.findCarried(player, s -> RadioSettings.readComms(s).alerts()).isEmpty();
    }

    /** Server config, read defensively: a notification fired before the config bakes still sends. */
    public static boolean requireRadio() {
        try {
            return SewvConfig.NOTIFICATIONS_REQUIRE_RADIO.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public static boolean voiceEnabled() {
        try {
            return SewvConfig.RADIO_VOICE_ENABLED.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
