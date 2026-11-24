package com.neoalive.tacz_sewv.compat.vosk;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import org.infinitytwogames.vosklib.VoskManager;
import org.vosk.Model;
import org.vosk.Recognizer;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.voice.MicSource;
import com.neoalive.tacz_sewv.client.voice.VoiceOrders;

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
    private static Recognizer recognizer;
    private static String recognizerGrammar;
    private static float minConfidence;
    private static int frames;

    private static volatile MicSource mic;
    // Client thread only.
    private static long startedAt;
    private static long releasedAt;
    private static boolean needsRelease;

    private VoiceSession() {}

    static boolean active() {
        return phase != Phase.IDLE;
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
                    phase = Phase.IDLE;
                    return;
                }
                synchronized (LOCK) {
                    if (recognizer == null || !grammar.equals(recognizerGrammar)) {
                        if (recognizer != null) recognizer.close();
                        recognizer = new Recognizer(model, SAMPLE_RATE, grammar);
                        recognizer.setWords(true);
                        recognizerGrammar = grammar;
                    } else {
                        recognizer.reset();
                    }
                    HEARD.setLength(0);
                    frames = 0;
                    minConfidence = minConf;
                }
                MicSource source = micFactory.get();
                if (!source.start(VoiceSession::feed)) {
                    notice(VoiceOrders.Notice.NO_MIC);
                    phase = Phase.IDLE;
                    return;
                }
                mic = source;
                phase = Phase.LISTENING;
            } catch (Throwable t) {
                TaczSewv.LOGGER.warn("Voice orders: recognizer setup failed", t);
                notice(VoiceOrders.Notice.FAILED);
                phase = Phase.IDLE;
            }
        });
    }

    private static void feed(byte[] pcm, int length) {
        synchronized (LOCK) {
            if (recognizer == null) return;
            if (recognizer.acceptWaveForm(pcm, length)) {
                append(recognizer.getResult());
            } else if (++frames % 2 == 0) {
                partial = (HEARD + " " + field(recognizer.getPartialResult(), "partial")).trim();
            }
        }
    }

    private static void close() {
        phase = Phase.CLOSING;
        MicSource source = mic;
        mic = null;
        WORKER.execute(() -> {
            String text = "";
            try {
                if (source != null) source.stop();
                synchronized (LOCK) {
                    if (recognizer != null) append(recognizer.getFinalResult());
                    text = HEARD.toString().trim();
                    HEARD.setLength(0);
                }
            } catch (Throwable t) {
                TaczSewv.LOGGER.warn("Voice orders: decode failed", t);
            } finally {
                partial = "";
                phase = Phase.IDLE;
            }
            String heard = text;
            Minecraft.getInstance().execute(() -> VoiceOrders.onHeard(heard));
        });
    }

    /** Words at or above the confidence floor, else the plain text when the result carries no words. */
    private static void append(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            StringBuilder out = new StringBuilder();
            if (o.has("result")) {
                for (JsonElement e : o.getAsJsonArray("result")) {
                    JsonObject w = e.getAsJsonObject();
                    if (w.get("conf").getAsFloat() >= minConfidence) out.append(w.get("word").getAsString()).append(' ');
                }
            } else if (o.has("text")) {
                out.append(o.get("text").getAsString());
            }
            String s = out.toString().trim();
            if (!s.isEmpty()) HEARD.append(s).append(' ');
        } catch (Exception ignored) {
            // A malformed result is dropped, the rest of the utterance still counts.
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
