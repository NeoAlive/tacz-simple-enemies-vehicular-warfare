package com.neoalive.tacz_sewv.client.voice;

/** Opens one capture device and reports how loud it is, so setup can show which device hears the player. */
public final class MicLevelTest {

    /** Level falls by this much per frame, so a word shows as a bar that settles rather than a flicker. */
    private static final float DECAY = 0.92f;

    private JavaSoundMic mic;
    private volatile float level;
    private volatile boolean opened;

    /** {@code device}: a device name, or blank for the system default. */
    public void start(String device) {
        stop();
        this.level = 0;
        this.mic = new JavaSoundMic(device);
        this.opened = this.mic.start((pcm, length) -> {
            int peak = 0;
            for (int i = 0; i + 1 < length; i += 2) {
                int v = Math.abs((short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8)));
                if (v > peak) peak = v;
            }
            this.level = Math.max(peak / 32768f, this.level * DECAY);
        });
        if (!this.opened) this.mic = null;
    }

    public void stop() {
        if (this.mic != null) this.mic.stop();
        this.mic = null;
        this.opened = false;
    }

    /** 0 to 1 on a log scale from -60 dBFS, which is how loud speech reads to a person. */
    public float level() {
        float l = this.level;
        if (l <= 0.001f) return 0;
        return Math.min(1f, (float) (1 + Math.log10(l) / 3));
    }

    public boolean opened() {
        return this.opened;
    }
}
