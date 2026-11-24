package com.neoalive.tacz_sewv.client.voice;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.TargetDataLine;

/** The default input device through Java Sound, opened per utterance and closed after it. */
public final class JavaSoundMic implements MicSource {

    private static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);
    /** 100 ms of audio per read. */
    private static final int CHUNK = 3200;

    private volatile boolean running;
    private TargetDataLine line;
    private Thread reader;

    @Override
    public boolean start(Sink sink) {
        try {
            this.line = AudioSystem.getTargetDataLine(FORMAT);
            this.line.open(FORMAT, CHUNK * 4);
            this.line.start();
        } catch (Exception e) {
            this.line = null;
            return false;
        }
        this.running = true;
        TargetDataLine l = this.line;
        this.reader = new Thread(() -> {
            byte[] buf = new byte[CHUNK];
            while (this.running) {
                int n = l.read(buf, 0, buf.length);
                if (n > 0) sink.accept(buf, n);
            }
        }, "sewv-voice-mic");
        this.reader.setDaemon(true);
        this.reader.start();
        return true;
    }

    @Override
    public void stop() {
        this.running = false;
        TargetDataLine l = this.line;
        if (l == null) return;
        l.stop();
        try {
            this.reader.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        l.close();
        this.line = null;
    }
}
