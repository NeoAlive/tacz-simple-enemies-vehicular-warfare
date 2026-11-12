package com.neoalive.tacz_sewv.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.neoalive.tacz_sewv.audio.RadioVoiceDsp.Chain;

/** Headless checks for the radio voice DSP and the Voice Chat isolation rule. */
public final class RadioDspSelfCheck {

    private static final int SR = RadioVoiceDsp.DEFAULT_RATE;
    private static final int FRAME = 960;

    /** Under compat/voicechat but loadable without Voice Chat: must not name its types. */
    private static final Set<String> VC_FREE = Set.of("VoicechatCompat.java", "RadioVoiceIds.java", "RadioVoiceNet.java", "RadioVoiceLoopback.java");

    public static void main(String[] args) throws IOException {
        bounded();
        bandLimited();
        deterministic();
        chunking();
        silence();
        budget();
        isolation();
        System.out.println("RadioDspSelfCheck OK");
    }

    /** Speech-like test signal: vowel-ish harmonics in syllables over pink-ish noise, loud. */
    private static short[] voice(int seconds) {
        int n = seconds * SR;
        short[] out = new short[n];
        long s = 42;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double f0 = 120 + 30 * Math.sin(2 * Math.PI * 0.7 * t);
            double v = 0;
            for (int h = 1; h <= 20; h++) v += Math.sin(2 * Math.PI * f0 * h * t) / h;
            double syll = Math.max(0, Math.sin(2 * Math.PI * 3 * t));
            s ^= s << 13;
            s ^= s >>> 7;
            s ^= s << 17;
            double noise = ((s >>> 11) * 0x1.0p-53 - 0.5) * 0.3;
            out[i] = (short) Math.round(Math.max(-1, Math.min(1, (v * 0.25 + noise) * syll)) * 32767 * 0.8);
        }
        return out;
    }

    private static short[] run(RadioVoiceDsp dsp, short[] in, int frame) {
        short[] out = new short[in.length];
        for (int i = 0; i < in.length; i += frame) {
            int len = Math.min(frame, in.length - i);
            short[] chunk = new short[len];
            System.arraycopy(in, i, chunk, 0, len);
            System.arraycopy(dsp.process(chunk), 0, out, i, len);
        }
        return out;
    }

    private static void bounded() {
        short[] out = run(new RadioVoiceDsp(1), voice(4), FRAME);
        int ceiling = (int) Math.ceil(RadioVoiceDsp.undb(-0.5) * 32767) + 1;
        for (short v : out) assert Math.abs(v) <= ceiling : "over ceiling: " + v;
    }

    private static double bandEnergy(short[] x, boolean lowpass, double f, int skip) {
        Chain c = Chain.of(Chain.butter(lowpass, 8, f, SR));
        double e = 0;
        for (int i = 0; i < x.length; i++) {
            double y = c.run(x[i] / 32768.0);
            if (i >= skip) e += y * y;
        }
        return e;
    }

    private static double energy(short[] x, int skip) {
        double e = 0;
        for (int i = skip; i < x.length; i++) e += (x[i] / 32768.0) * (x[i] / 32768.0);
        return e;
    }

    /**
     * Below 200 Hz and above 4 kHz carry little of the output, and much less than of the input. On a
     * real crew voice clip the script leaves -32 / -24 dB there and this port -34 / -25 dB; the synthetic
     * signal here is noisier, so the bounds are looser.
     */
    private static void bandLimited() {
        short[] in = voice(4);
        short[] out = run(new RadioVoiceDsp(2), in, FRAME);
        int skip = SR / 2;
        double inTot = energy(in, skip), outTot = energy(out, skip);
        double lowIn = 10 * Math.log10(bandEnergy(in, true, 200, skip) / inTot);
        double hiIn = 10 * Math.log10(bandEnergy(in, false, 4000, skip) / inTot);
        double lowOut = 10 * Math.log10(bandEnergy(out, true, 200, skip) / outTot);
        double hiOut = 10 * Math.log10(bandEnergy(out, false, 4000, skip) / outTot);
        System.out.printf("band: <200 Hz %.1f dB (in %.1f), >4 kHz %.1f dB (in %.1f)%n", lowOut, lowIn, hiOut, hiIn);
        assert lowOut <= -25 && lowOut <= lowIn - 12 : "low band not cut: " + lowOut;
        assert hiOut <= -18 && hiOut <= hiIn - 12 : "high band not cut: " + hiOut;
    }

    private static void deterministic() {
        short[] in = voice(2);
        short[] a = run(new RadioVoiceDsp(7), in, FRAME);
        short[] b = run(new RadioVoiceDsp(7), in, FRAME);
        assert java.util.Arrays.equals(a, b) : "same seed differs";
        short[] c = run(new RadioVoiceDsp(8), in, FRAME);
        assert !java.util.Arrays.equals(a, c) : "different seeds agree";
        RadioVoiceDsp dsp = new RadioVoiceDsp(7);
        run(dsp, in, FRAME);
        dsp.reset();
        assert java.util.Arrays.equals(a, run(dsp, in, FRAME)) : "reset does not restart the stream";
    }

    private static void chunking() {
        short[] in = voice(2);
        assert java.util.Arrays.equals(run(new RadioVoiceDsp(3), in, 960), run(new RadioVoiceDsp(3), in, 480))
                : "output depends on frame size";
        assert java.util.Arrays.equals(run(new RadioVoiceDsp(3), in, 960), run(new RadioVoiceDsp(3), in, 137))
                : "output depends on frame size";
    }

    /** Silence in leaves only the static bed, near the script's -46 dBFS base level. */
    private static void silence() {
        short[] out = run(new RadioVoiceDsp(4), new short[SR * 3], FRAME);
        double rms = Math.sqrt(energy(out, SR) / (out.length - SR));
        double db = 20 * Math.log10(rms);
        System.out.printf("silence: %.1f dBFS%n", db);
        assert db > -50 && db < -30 : "noise bed off: " + db;
    }

    private static void budget() {
        short[] in = voice(10);
        RadioVoiceDsp dsp = new RadioVoiceDsp(5);
        run(dsp, in, FRAME);
        long t0 = System.nanoTime();
        run(dsp, in, FRAME);
        double perFrameMs = (System.nanoTime() - t0) / 1e6 / (in.length / (double) FRAME);
        System.out.printf("budget: %.3f ms per %d-sample frame%n", perFrameMs, FRAME);
        assert perFrameMs < 0.6 : "too slow: " + perFrameMs + " ms";
    }

    private static void isolation() throws IOException {
        Path root = Path.of("src/main/java/com/neoalive/tacz_sewv");
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                if (!src.contains("de.maxhenkel.voicechat")) continue;
                boolean inCompat = p.toString().replace('\\', '/').contains("/compat/voicechat/");
                if (!inCompat || VC_FREE.contains(p.getFileName().toString())) bad.add(root.relativize(p).toString());
            }
        }
        assert bad.isEmpty() : "Voice Chat types outside the plugin classes: " + bad;
    }
}
