package com.neoalive.tacz_sewv.compat.voicechat;

import net.minecraftforge.fml.ModList;

/**
 * The only Voice Chat entry point shared code may call. Names no {@code de.maxhenkel} type, so it loads on
 * every install; the plugin classes beside it are reached only by Voice Chat itself.
 */
public final class VoicechatCompat {

    public static final String MOD_ID = "voicechat";

    private static volatile boolean serverActive;
    private static volatile double proximity = 48;

    private VoicechatCompat() {}

    public static boolean present() {
        return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
    }

    /** Voice Chat's server is up, so radio voice can be relayed. */
    public static boolean serverActive() {
        return serverActive;
    }

    /** Voice Chat's proximity distance: receivers inside it already hear the speaker directly. */
    public static double proximity() {
        return proximity;
    }

    static void serverStarted(double voiceDistance) {
        proximity = voiceDistance;
        serverActive = true;
    }

    static void serverStopped() {
        serverActive = false;
        RadioVoiceNet.clear();
    }
}
