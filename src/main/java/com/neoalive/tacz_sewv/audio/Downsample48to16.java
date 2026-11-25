package com.neoalive.tacz_sewv.audio;

import java.util.Arrays;

/**
 * Voice Chat's 48 kHz microphone frames to the 16 kHz the recognizer wants: a windowed sinc low pass at
 * 7 kHz, then every third sample, written as signed 16-bit little endian. Keeps its filter history and its
 * decimation phase across calls, so the output does not depend on how the input is chunked. Pure.
 */
public final class Downsample48to16 {

    public static final int FACTOR = 3;
    private static final int TAPS = 47;
    private static final double CUTOFF = 7000.0 / 48000.0;
    private static final double[] KERNEL = kernel();

    private final double[] history = new double[TAPS];
    private int head;
    private int phase;

    private static double[] kernel() {
        double[] k = new double[TAPS];
        int mid = TAPS / 2;
        double sum = 0;
        for (int i = 0; i < TAPS; i++) {
            int n = i - mid;
            double sinc = n == 0 ? 2 * CUTOFF : Math.sin(2 * Math.PI * CUTOFF * n) / (Math.PI * n);
            double blackman = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (TAPS - 1)) + 0.08 * Math.cos(4 * Math.PI * i / (TAPS - 1));
            k[i] = sinc * blackman;
            sum += k[i];
        }
        for (int i = 0; i < TAPS; i++) k[i] /= sum;
        return k;
    }

    public void reset() {
        Arrays.fill(this.history, 0);
        this.head = 0;
        this.phase = 0;
    }

    /** Filter and decimate {@code in}. Returns the 16 kHz bytes; may be empty for a very short frame. */
    public byte[] process(short[] in) {
        byte[] out = new byte[(in.length / FACTOR + 1) * 2];
        int o = 0;
        for (short s : in) {
            this.history[this.head] = s;
            this.head = (this.head + 1) % TAPS;
            if (this.phase++ % FACTOR != 0) continue;
            double acc = 0;
            int idx = this.head;
            for (int t = 0; t < TAPS; t++) {
                acc += this.history[idx] * KERNEL[t];
                idx = idx + 1 == TAPS ? 0 : idx + 1;
            }
            int v = (int) Math.round(Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, acc)));
            out[o++] = (byte) v;
            out[o++] = (byte) (v >> 8);
        }
        this.phase %= FACTOR;
        return o == out.length ? out : Arrays.copyOf(out, o);
    }
}
