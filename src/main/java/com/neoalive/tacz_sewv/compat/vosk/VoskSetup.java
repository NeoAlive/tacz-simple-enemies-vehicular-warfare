package com.neoalive.tacz_sewv.compat.vosk;

import java.nio.file.Files;
import java.nio.file.Path;

import org.infinitytwogames.vosklib.VoskManager;
import org.infinitytwogames.vosklib.data.DataLoader;

/**
 * Setup through VoskLib's own calls, the same ones its model screen makes (download, unzip and register in
 * {@code startDownload}, then {@code select}, {@code save} and {@code VoskManager.init}), so its screen and
 * config always agree with what setup did.
 */
final class VoskSetup {

    private VoskSetup() {}

    static boolean configured() {
        String name = DataLoader.getSelected();
        Path path = DataLoader.getSelectedPath();
        return name != null && !name.equals("none") && path != null && Files.isDirectory(path);
    }

    static boolean downloaded(String model) {
        return DataLoader.isModelDownloaded(model);
    }

    /** Download if needed, then select and load. Callbacks run off the client thread. */
    static void install(String model, String url, Runnable done, Runnable fail) {
        if (downloaded(model)) {
            Thread t = new Thread(() -> use(model, done, fail), "sewv-voice-setup");
            t.setDaemon(true);
            t.start();
            return;
        }
        DataLoader.startDownload(model, url, () -> use(model, done, fail), fail);
    }

    private static void use(String model, Runnable done, Runnable fail) {
        try {
            DataLoader.select(model);
            DataLoader.save();
            VoskManager.init();
            if (VoskManager.getModel() != null) done.run();
            else fail.run();
        } catch (Throwable t) {
            fail.run();
        }
    }

    static long bytesDone() {
        return DataLoader.downloadedBytes.get();
    }

    static long bytesTotal() {
        return DataLoader.totalSize.get();
    }
}
