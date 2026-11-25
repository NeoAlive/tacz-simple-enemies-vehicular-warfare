package com.neoalive.tacz_sewv.compat.vosk;

import java.io.IOException;
import java.nio.file.Files;
import java.util.function.Supplier;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.voice.MicSource;

/**
 * The only VoskLib entry point shared code may call. Names no Vosk or VoskLib type, so it loads on every
 * install; {@link VoiceSession} beside it is reached only once {@link #present()} is true.
 */
public final class VoskCompat {

    public static final String MOD_ID = "vosklib";

    private VoskCompat() {}

    public static boolean present() {
        return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
    }

    /**
     * VoskLib keeps its model list in {@code config/vosk/manifest.json} but never creates the folder before
     * the first fetch, so on a fresh install the write fails and its model screen reports "Could not reach
     * Vosk servers" with nothing logged. Creating the folder is all it needs.
     */
    public static void ensureModelDir() {
        try {
            Files.createDirectories(FMLPaths.CONFIGDIR.get().resolve("vosk"));
        } catch (IOException e) {
            TaczSewv.LOGGER.warn("Voice orders: could not create config/vosk", e);
        }
    }

    /** The model VoskLib has selected, or null with none or without VoskLib. */
    @javax.annotation.Nullable
    public static String modelName() {
        return present() ? VoiceSession.modelName() : null;
    }

    /** VoskLib has a model selected that exists on disk. */
    public static boolean configured() {
        return present() && VoskSetup.configured();
    }

    /** Download a model if missing, then select and load it. Callbacks run off the client thread. */
    public static void install(String model, String url, Runnable done, Runnable fail) {
        if (present()) VoskSetup.install(model, url, done, fail);
        else fail.run();
    }

    /** Download progress: bytes so far and total (0 while unknown). */
    public static long[] progress() {
        return present() ? new long[] {VoskSetup.bytesDone(), VoskSetup.bytesTotal()} : new long[] {0, 0};
    }

    /** A voice order is being listened to or decoded. */
    public static boolean active() {
        return present() && VoiceSession.active();
    }

    /** What has been heard so far in the current utterance. */
    public static String partial() {
        return present() ? VoiceSession.partial() : "";
    }

    /** Client tick: the push to talk key state drives the session. */
    public static void tick(boolean keyDown, String grammar, float minConfidence, Supplier<MicSource> mic) {
        if (present()) VoiceSession.tick(keyDown, grammar, minConfidence, mic);
    }
}
