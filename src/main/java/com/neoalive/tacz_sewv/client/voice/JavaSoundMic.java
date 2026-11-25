package com.neoalive.tacz_sewv.client.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * An input device through Java Sound, opened per utterance and closed after it. The system default is
 * often not the microphone on Linux (a PipeWire default source pointing elsewhere records silence), so a
 * device can be named by part of its name; the available ones are logged once.
 */
public final class JavaSoundMic implements MicSource {

    private static final DataLine.Info LINE = new DataLine.Info(TargetDataLine.class, new AudioFormat(16000f, 16, 1, true, false));
    private static boolean listed;

    private final String wanted;
    private String device = "default";

    private static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);
    /** 100 ms of audio per read. */
    private static final int CHUNK = 3200;

    private volatile boolean running;
    private TargetDataLine line;
    private Thread reader;

    /** {@code wanted}: part of a device name or description, blank for the system default. */
    public JavaSoundMic(String wanted) {
        this.wanted = wanted == null ? "" : wanted.trim().toLowerCase(Locale.ROOT);
    }

    /** Every device that can record a voice order, as "name | description". */
    public static List<String> devices() {
        List<String> out = new ArrayList<>();
        for (Mixer.Info m : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(m).isLineSupported(LINE)) out.add(m.getName() + " | " + m.getDescription());
        }
        return out;
    }

    /** The names of the devices {@link #devices()} lists, in the same order. */
    public static List<String> deviceNames() {
        List<String> out = new ArrayList<>();
        for (Mixer.Info m : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(m).isLineSupported(LINE)) out.add(m.getName());
        }
        return out;
    }

    private TargetDataLine open() throws Exception {
        if (!listed) {
            listed = true;
            TaczSewv.LOGGER.info("Voice orders: capture devices {}", devices());
        }
        if (!this.wanted.isEmpty()) {
            for (Mixer.Info m : AudioSystem.getMixerInfo()) {
                String name = (m.getName() + " " + m.getDescription()).toLowerCase(Locale.ROOT);
                Mixer mixer = AudioSystem.getMixer(m);
                if (name.contains(this.wanted) && mixer.isLineSupported(LINE)) {
                    this.device = m.getName();
                    return (TargetDataLine) mixer.getLine(LINE);
                }
            }
            TaczSewv.LOGGER.warn("Voice orders: no capture device matches '{}', using the default", this.wanted);
        }
        this.device = "default";
        return AudioSystem.getTargetDataLine(FORMAT);
    }

    @Override
    public String describe() {
        return this.device;
    }

    @Override
    public boolean start(Sink sink) {
        try {
            this.line = open();
            this.line.open(FORMAT, CHUNK * 4);
            this.line.start();
        } catch (Exception e) {
            TaczSewv.LOGGER.warn("Voice orders: could not open capture device {}", this.device, e);
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
