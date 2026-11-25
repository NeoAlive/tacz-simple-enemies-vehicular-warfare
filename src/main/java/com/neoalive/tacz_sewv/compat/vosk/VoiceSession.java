package com.neoalive.tacz_sewv.compat.vosk;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import org.infinitytwogames.vosklib.VoskManager;
import org.infinitytwogames.vosklib.data.DataLoader;
import org.vosk.Model;
import org.vosk.Recognizer;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.voice.MicSource;
import com.neoalive.tacz_sewv.client.voice.VoiceOrders;
import com.neoalive.tacz_sewv.client.voice.VoskJson;

/**
 * One push to talk utterance at a time. VoskLib supplies the natives and the model only: its own
 * {@code VoskManager} listening loop toasts on every start and stop, reports a result only on a pause, and
 * drops the final result when stopped, which loses an order the moment the key comes up mid sentence.
 *
 * <p>The client tick drives the phases; model and recognizer setup, and the final decode, run on one worker
 * thread; frames arrive on the microphone's thread. The recognizer is only touched under {@link #LOCK}.
 * The model is VoskLib's own, loaded at its startup, so it is never held twice.
 */
final class VoiceSession {

    /** Audio kept after the key comes up, so the last word is not clipped. */
    private static final long TAIL_MS = 250;
    private static final long MAX_MS = 8000;
    private static final float SAMPLE_RATE = 16000f;

    private enum Phase { IDLE, OPENING, LISTENING, CLOSING }

    private static final Object LOCK = new Object();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sewv-voice");
        t.setDaemon(true);
        return t;
    });

    private static volatile Phase phase = Phase.IDLE;
    private static volatile String partial = "";
    private static final StringBuilder HEARD = new StringBuilder();
    /** Every word with its confidence, kept or not, for the log. */
    private static final StringBuilder RAW = new StringBuilder();
    /** Below this peak the whole utterance was silence: the device records nothing. About -60 dBFS. */
    private static final int SILENT_PEAK = 33;

    private static Recognizer recognizer;
    private static int peak;
    private static long bytes;
    private static String source = "";
    private static String recognizerGrammar;
    private static Model recognizerModel;
    private static float minConfidence;
    private static int frames;

    private static volatile MicSource mic;
    // Client thread only.
    private static long startedAt;
    private static long releasedAt;
    /** A held key does not retry until released, after a failure or the time limit. */
    private static volatile boolean needsRelease;

    private VoiceSession() {}

    static boolean active() {
        return phase != Phase.IDLE;
    }

    static String modelName() {
        String name = DataLoader.getSelected();
        return name == null || name.equals("none") ? null : name;
    }

    static String partial() {
        return partial;
    }

    static void tick(boolean down, String grammar, float minConf, Supplier<MicSource> micFactory) {
        long now = System.currentTimeMillis();
        if (!down) needsRelease = false;
        switch (phase) {
            case IDLE -> {
                if (down && !needsRelease) open(grammar, minConf, micFactory, now);
            }
            case OPENING, LISTENING -> {
                if (down) releasedAt = 0;
                else if (releasedAt == 0) releasedAt = now;
                boolean tailDone = releasedAt != 0 && now - releasedAt >= TAIL_MS;
                boolean tooLong = now - startedAt >= MAX_MS;
                if (phase == Phase.LISTENING && (tailDone || tooLong)) {
                    if (tooLong && down) needsRelease = true;
                    close();
                }
            }
            case CLOSING -> {
            }
        }
    }

    private static void open(String grammar, float minConf, Supplier<MicSource> micFactory, long now) {
        phase = Phase.OPENING;
        startedAt = now;
        releasedAt = 0;
        partial = "";
        WORKER.execute(() -> {
            try {
                Model model = VoskManager.getModel();
                if (model == null) {
                    VoskManager.init();
                    model = VoskManager.getModel();
                }
                if (model == null) {
                    notice(VoiceOrders.Notice.NO_MODEL);
                    needsRelease = true;
                    phase = Phase.IDLE;
                    return;
                }
                synchronized (LOCK) {
                    // VoskLib reloads its model when another is picked and frees the old one, so a recognizer
                    // built on it must not be reused.
                    if (recognizer == null || !grammar.equals(recognizerGrammar) || model != recognizerModel) {
                        if (recognizer != null) recognizer.close();
                        recognizer = new Recognizer(model, SAMPLE_RATE, grammar);
                        recognizer.setWords(true);
                        recognizerGrammar = grammar;
                        recognizerModel = model;
                    } else {
                        recognizer.reset();
                    }
                    HEARD.setLength(0);
                    RAW.setLength(0);
                    frames = 0;
                    peak = 0;
                    bytes = 0;
                    minConfidence = minConf;
                }
                MicSource opened = micFactory.get();
                if (!opened.start(VoiceSession::feed)) {
                    notice(VoiceOrders.Notice.NO_MIC);
                    needsRelease = true;
                    phase = Phase.IDLE;
                    return;
                }
                source = opened.describe();
                mic = opened;
                phase = Phase.LISTENING;
            } catch (Throwable t) {
                TaczSewv.LOGGER.warn("Voice orders: recognizer setup failed", t);
                notice(VoiceOrders.Notice.FAILED);
                needsRelease = true;
                phase = Phase.IDLE;
            }
        });
    }

    private static void feed(byte[] pcm, int length) {
        synchronized (LOCK) {
            if (recognizer == null) return;
            bytes += length;
            for (int i = 0; i + 1 < length; i += 2) {
                int v = Math.abs((short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8)));
                if (v > peak) peak = v;
            }
            if (recognizer.acceptWaveForm(pcm, length)) {
                append(recognizer.getResult());
            } else if (++frames % 2 == 0) {
                partial = (HEARD + " " + field(recognizer.getPartialResult(), "partial")).trim();
            }
        }
    }

    private static void close() {
        phase = Phase.CLOSING;
        MicSource opened = mic;
        mic = null;
        WORKER.execute(() -> {
            String text = "";
            boolean silent = false;
            String from = source;
            try {
                if (opened != null) opened.stop();
                synchronized (LOCK) {
                    if (recognizer != null) append(recognizer.getFinalResult());
                    text = HEARD.toString().trim();
                    HEARD.setLength(0);
                    silent = peak < SILENT_PEAK;
                    double db = peak == 0 ? Double.NEGATIVE_INFINITY : 20 * Math.log10(peak / 32768.0);
                    TaczSewv.LOGGER.info("Voice orders: {} ms from {}, peak {} dBFS, words [{}], kept '{}'",
                            bytes / 32, from, String.format(java.util.Locale.ROOT, "%.1f", db), RAW.toString().trim(), text);
                    RAW.setLength(0);
                }
            } catch (Throwable t) {
                TaczSewv.LOGGER.warn("Voice orders: decode failed", t);
            } finally {
                partial = "";
                phase = Phase.IDLE;
            }
            String heard = text;
            boolean quiet = silent;
            Minecraft.getInstance().execute(() -> VoiceOrders.onHeard(heard, from, quiet));
        });
    }

    /** Words at or above the confidence floor, else the plain text when the result carries no words. */
    private static void append(String json) {
        try {
            JsonObject o = JsonParser.parseString(VoskJson.fixDecimals(json)).getAsJsonObject();
            StringBuilder out = new StringBuilder();
            if (o.has("result")) {
                for (JsonElement e : o.getAsJsonArray("result")) {
                    JsonObject w = e.getAsJsonObject();
                    float conf = w.get("conf").getAsFloat();
                    String word = w.get("word").getAsString();
                    RAW.append(word).append('(').append(String.format(java.util.Locale.ROOT, "%.2f", conf)).append(") ");
                    if (conf >= minConfidence) out.append(word).append(' ');
                }
            } else if (o.has("text")) {
                out.append(o.get("text").getAsString());
            }
            String s = out.toString().trim();
            if (!s.isEmpty()) HEARD.append(s).append(' ');
        } catch (Exception e) {
            // A malformed result is dropped, the rest of the utterance still counts.
            TaczSewv.LOGGER.warn("Voice orders: unreadable recognizer result {}", json.replace('\n', ' '));
        }
    }

    private static String field(String json, String key) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            return o.has(key) ? o.get(key).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static void notice(VoiceOrders.Notice notice) {
        Minecraft.getInstance().execute(() -> VoiceOrders.onNotice(notice));
    }
}
