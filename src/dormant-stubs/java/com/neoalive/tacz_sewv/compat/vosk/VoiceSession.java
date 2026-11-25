// NeoForge port branch: DORMANT-INTEGRATION STUB for VoskLib (no 1.21.1 build). The real class is
// excluded by dormantSources in build.gradle.
package com.neoalive.tacz_sewv.compat.vosk;

import java.util.function.Supplier;

import com.neoalive.tacz_sewv.client.voice.MicSource;

final class VoiceSession {

    private VoiceSession() {}

    static boolean active() {
        return false;
    }

    static String modelName() {
        return null;
    }

    static boolean listening() {
        return false;
    }

    static String partial() {
        return "";
    }

    static void tick(boolean down, String grammar, float minConf, Supplier<MicSource> micFactory) {}
}
