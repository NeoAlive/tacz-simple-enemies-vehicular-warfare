package com.neoalive.tacz_sewv.compat.voicechat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Debug echo for radio voice ({@code /sewv debug voiceTest}): a speaker's own transmissions come back to
 * them after a delay on their own radio channel, so the client runs the full receive path (DSP, clicks,
 * RX readout) on their voice. The net and ally filter is the one part it skips.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class RadioVoiceLoopback {

    public static final int DEFAULT_DELAY_SECONDS = 2;
    public static final int MAX_DELAY_SECONDS = 10;

    private static final Map<UUID, Integer> DELAY_MS = new ConcurrentHashMap<>();

    private RadioVoiceLoopback() {}

    public static void enable(UUID player, int delaySeconds) {
        int s = Math.max(1, Math.min(MAX_DELAY_SECONDS, delaySeconds));
        DELAY_MS.put(player, s * 1000);
    }

    public static boolean disable(UUID player) {
        return DELAY_MS.remove(player) != null;
    }

    /** Echo delay in ms, or -1 when this player has no echo. Any thread. */
    static int delayMs(UUID player) {
        Integer d = DELAY_MS.get(player);
        return d == null ? -1 : d;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        DELAY_MS.remove(event.getEntity().getUUID());
    }
}
