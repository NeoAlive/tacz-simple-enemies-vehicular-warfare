// NeoForge port branch: DORMANT-INTEGRATION STUB for VoskLib (no 1.21.1 build). The real class is
// excluded by dormantSources in build.gradle.
package com.neoalive.tacz_sewv.compat.vosk;

final class VoskSetup {

    private VoskSetup() {}

    static boolean configured() {
        return false;
    }

    static boolean downloaded(String model) {
        return false;
    }

    static void install(String model, String url, Runnable done, Runnable fail) {
        fail.run();
    }

    static long bytesDone() {
        return 0;
    }

    static long bytesTotal() {
        return 0;
    }
}
