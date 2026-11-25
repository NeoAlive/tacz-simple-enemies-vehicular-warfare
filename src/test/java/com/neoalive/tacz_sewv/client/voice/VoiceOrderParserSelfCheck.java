package com.neoalive.tacz_sewv.client.voice;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.neoalive.tacz_sewv.audio.Downsample48to16;
import com.neoalive.tacz_sewv.client.voice.VoiceOrderParser.Result;
import com.neoalive.tacz_sewv.client.voice.VoiceOrderParser.Status;
import com.neoalive.tacz_sewv.map.VehicleMarker;

/**
 * Headless checks for voice orders. Run with {@code ./gradlew selfCheckVoice}.
 *
 * <ol>
 *   <li>Every order phrase, said alone, parses to its own order: no two orders share a phrase.</li>
 *   <li>Every scope phrase in front of an order parses to that scope and that order.</li>
 *   <li>Every order id is a real wheel pipeline id, every scope token is well formed.</li>
 *   <li>Free text as a large model would hear it still parses, and junk does not.</li>
 *   <li>Platoon hues name the band they sit in.</li>
 *   <li>Voice Chat frames resample to 16 kHz: speech band kept, alias band cut, chunking irrelevant.</li>
 *   <li>Vosk results written with a decimal comma (Italian and other locales) still parse.</li>
 *   <li>Only {@code compat/vosk} names a Vosk or VoskLib type.</li>
 * </ol>
 */
public final class VoiceOrderParserSelfCheck {

    private static final Path SRC = Path.of("src/main/java/com/neoalive/tacz_sewv");

    public static void main(String[] args) throws IOException {
        VoicePhrases table = VoicePhrases.load(VoicePhrases.DEFAULT_LANG);
        assert table != null : "en_us voice table did not load";
        assert VoicePhrases.load("xx_yy") != null : "unknown language must fall back to English";
        for (String lang : new java.util.TreeSet<>(VoicePhrases.FAMILY.values())) {
            VoicePhrases t = VoicePhrases.load(lang);
            assert t != null : lang + " voice table did not load";
            assert !lang.equals(VoicePhrases.DEFAULT_LANG) || t == table || t.orders().equals(table.orders());
            assert t.orders().keySet().equals(table.orders().keySet()) : lang + " orders differ from en_us";
            assert t.scopes().keySet().equals(table.scopes().keySet()) : lang + " scopes differ from en_us";
            phrases(t);
            scopes(t);
            ids(t);
        }
        languages();
        freeText(table);
        grammar(table);
        colours();
        downsample();
        voskJson();
        isolation();
        System.out.println("VoiceOrderParserSelfCheck OK");
    }

    private static void phrases(VoicePhrases table) {
        for (Map.Entry<String, List<List<String>>> e : table.orders().entrySet()) {
            for (List<String> p : e.getValue()) {
                Result r = VoiceOrderParser.parse(table, String.join(" ", p));
                assert r.status() == Status.OK && e.getKey().equals(r.pipelineId())
                        : "phrase '" + String.join(" ", p) + "' gave " + r + ", want " + e.getKey();
            }
        }
    }

    private static void scopes(VoicePhrases table) {
        for (Map.Entry<String, List<List<String>>> e : table.scopes().entrySet()) {
            for (List<String> p : e.getValue()) {
                String said = String.join(" ", p) + " " + longestHold(table);
                Result r = VoiceOrderParser.parse(table, said);
                assert r.status() == Status.OK && "quick_hold".equals(r.pipelineId())
                        : "'" + said + "' lost its order: " + r;
                assert e.getKey().equals(r.scope()) : "'" + said + "' gave scope " + r.scope() + ", want " + e.getKey();
            }
        }
    }

    private static String longestHold(VoicePhrases table) {
        List<String> best = List.of();
        for (List<String> p : table.orders().get("quick_hold")) if (p.size() > best.size()) best = p;
        return String.join(" ", best);
    }

    /** A model's name picks its table, and a locale without one falls back inside its language. */
    private static void languages() {
        assert "it_it".equals(VoicePhrases.langOfModel("vosk-model-small-it-0.22"));
        assert "en_us".equals(VoicePhrases.langOfModel("vosk-model-small-en-us-0.15"));
        assert "en_us".equals(VoicePhrases.langOfModel("vosk-model-en-us-0.22-lgraph"));
        assert "en_us".equals(VoicePhrases.langOfModel("vosk-model-small-en-gb-0.15"));
        assert "zh_cn".equals(VoicePhrases.langOfModel("vosk-model-small-cn-0.22"));
        assert "pt_br".equals(VoicePhrases.langOfModel("vosk-model-small-pt-0.3"));
        assert "de_de".equals(VoicePhrases.langOfModel("vosk-model-small-de-0.15"));
        assert VoicePhrases.langOfModel("vosk-model-small-ja-0.22") == null : "no Japanese table yet";
        assert VoicePhrases.langOfModel("none") == null;
        assert VoicePhrases.langOfModel(null) == null;
        VoicePhrases mx = VoicePhrases.load("es_mx");
        assert mx.orders().equals(VoicePhrases.load("es_es").orders()) : "es_mx must use the Spanish table";
        assert VoicePhrases.load("pt_pt").orders().equals(VoicePhrases.load("pt_br").orders()) : "pt_pt must use pt_br";
        Result it = VoiceOrderParser.parse(VoicePhrases.load("it_it"), "carri armati attaccate quello");
        assert it.status() == Status.OK && "quick_attack".equals(it.pipelineId()) && it.aimed()
                && "KIND:ARMOR,MECHANIZED,ANTI_AIR".equals(it.scope()) : "Italian sample gave " + it;
        Result zh = VoiceOrderParser.parse(VoicePhrases.load("zh_cn"), "全体 攻击 那个");
        assert zh.status() == Status.OK && "quick_attack".equals(zh.pipelineId()) && "ALL".equals(zh.scope())
                && zh.aimed() : "Chinese sample gave " + zh;
        Result ru = VoiceOrderParser.parse(VoicePhrases.load("ru_ru"), "танки прекратить огонь");
        assert ru.status() == Status.OK && "quick_cease_fire".equals(ru.pipelineId()) : "Russian sample gave " + ru;
    }

    private static void ids(VoicePhrases table) throws IOException {
        String registry = Files.readString(SRC.resolve("command/quick/QuickCommandRegistry.java"), StandardCharsets.UTF_8);
        Set<String> known = new HashSet<>();
        Matcher m = Pattern.compile("String ID_[A-Z_]+ = \"([a-z_]+)\"").matcher(registry);
        while (m.find()) known.add(m.group(1));
        assert known.size() > 30 : "registry scan found only " + known.size() + " ids";
        for (String id : table.orders().keySet()) assert known.contains(id) : "unknown pipeline id " + id;

        for (String scope : table.scopes().keySet()) {
            if (scope.equals(VoiceScope.ALL) || scope.equals(VoiceScope.SELECTED)) continue;
            if (scope.startsWith(VoiceScope.KIND)) {
                for (String k : scope.substring(VoiceScope.KIND.length()).split(",")) VehicleMarker.Kind.valueOf(k);
            } else if (scope.startsWith(VoiceScope.PLATOON)) {
                String c = scope.substring(VoiceScope.PLATOON.length());
                assert PlatoonColours.NAMES.contains(c) : "unknown platoon colour " + c;
            } else {
                throw new AssertionError("bad scope token " + scope);
            }
        }
    }

    private static void freeText(VoicePhrases table) {
        expect(table, "uh okay tanks hold position now please", "quick_hold", "KIND:ARMOR,MECHANIZED,ANTI_AIR", false);
        expect(table, "everyone go there", "quick_move", "ALL", true);
        expect(table, "artillery strike", "support_artillery", null, false);
        expect(table, "artillery hold fire", "quick_cease_fire", "KIND:EMPLACEMENT,ARTILLERY,MISSILE_SYSTEM", false);
        expect(table, "infantry [unk] dig in", "quick_entrench", "KIND:INFANTRY,INFANTRY_MEDIC,INFANTRY_ENGINEER,"
                + "INFANTRY_COMBAT_ENGINEER,INFANTRY_COMMANDER", false);
        expect(table, "Platoon Red, attack that!", "quick_attack", "PLATOON:red", true);
        expect(table, "everybody out", "quick_dismount", null, false);
        expect(table, "hold the line", "quick_hold", null, false);
        assert VoiceOrderParser.parse(table, "").status() == Status.EMPTY;
        assert VoiceOrderParser.parse(table, "[unk] [unk]").status() == Status.EMPTY;
        assert VoiceOrderParser.parse(table, "banana sandwich").status() == Status.UNKNOWN;
        assert VoiceOrderParser.parse(table, "hold wedge").status() == Status.AMBIGUOUS;
    }

    private static void expect(VoicePhrases table, String said, String id, String scope, boolean aimed) {
        Result r = VoiceOrderParser.parse(table, said);
        assert r.status() == Status.OK && id.equals(r.pipelineId())
                && java.util.Objects.equals(scope, r.scope()) && r.aimed() == aimed
                : "'" + said + "' gave " + r;
    }

    private static void grammar(VoicePhrases table) {
        String json = table.grammarJson();
        assert json.contains("\"[unk]\"") : "grammar must keep [unk]";
        for (List<List<String>> ps : table.orders().values()) {
            for (List<String> p : ps) for (String w : p) assert json.contains("\"" + w + "\"") : "grammar misses " + w;
        }
    }

    private static void colours() {
        String[] want = {"red", "orange", "yellow", "green", "cyan", "blue", "purple", "pink"};
        float[] hues = {0, 30, 57, 120, 180, 225, 275, 315};
        for (int i = 0; i < hues.length; i++) {
            int rgb = Color.HSBtoRGB(hues[i] / 360f, 0.65f, 0.95f) & 0xFFFFFF;
            assert want[i].equals(PlatoonColours.nameOf(rgb)) : "hue " + hues[i] + " named " + PlatoonColours.nameOf(rgb);
        }
        assert PlatoonColours.nameOf(Color.HSBtoRGB(355 / 360f, 0.65f, 0.95f) & 0xFFFFFF).equals("red");
    }

    private static void downsample() {
        double pass = rmsRatio(1000), stop = rmsRatio(10000);
        assert pass > 0.95 && pass < 1.05 : "1 kHz kept at " + pass;
        assert stop < 0.03 : "10 kHz leaked at " + stop;

        short[] in = sine(700, 48000);
        byte[] whole = new Downsample48to16().process(in);
        Downsample48to16 chunked = new Downsample48to16();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int[] sizes = {960, 7, 1, 480, 2000};
        for (int at = 0, k = 0; at < in.length; k++) {
            int n = Math.min(sizes[k % sizes.length], in.length - at);
            out.writeBytes(chunked.process(java.util.Arrays.copyOfRange(in, at, at + n)));
            at += n;
        }
        assert java.util.Arrays.equals(whole, out.toByteArray()) : "chunking changed the output";
        assert whole.length == 16000 * 2 : "48000 samples gave " + whole.length / 2;
    }

    private static double rmsRatio(double hz) {
        short[] in = sine(hz, 48000);
        byte[] out = new Downsample48to16().process(in);
        double si = 0, so = 0;
        for (int i = 4800; i < in.length; i++) si += (double) in[i] * in[i];
        int from = 1600, n = out.length / 2;
        for (int i = from; i < n; i++) {
            double v = (short) ((out[2 * i] & 0xFF) | (out[2 * i + 1] << 8));
            so += v * v;
        }
        return Math.sqrt(so / (n - from)) / Math.sqrt(si / (in.length - 4800));
    }

    private static short[] sine(double hz, int samples) {
        short[] s = new short[samples];
        for (int i = 0; i < samples; i++) s[i] = (short) Math.round(12000 * Math.sin(2 * Math.PI * hz * i / 48000));
        return s;
    }

    private static void voskJson() {
        String comma = "{  \"result\" : [{ \"conf\" : 0,474027, \"end\" : 0,840000, \"start\" : -1,5, "
                + "\"word\" : \"attack\" }, { \"conf\" : 1, \"word\" : \"that\" }], \"text\" : \"attack that\" }";
        com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(VoskJson.fixDecimals(comma)).getAsJsonObject();
        com.google.gson.JsonObject w = o.getAsJsonArray("result").get(0).getAsJsonObject();
        assert Math.abs(w.get("conf").getAsFloat() - 0.474027f) < 1e-6 : "conf read as " + w.get("conf");
        assert Math.abs(w.get("start").getAsFloat() + 1.5f) < 1e-6 : "start read as " + w.get("start");
        assert o.get("text").getAsString().equals("attack that");
        String dot = "{\"conf\" : 0.5, \"text\" : \"a, b\"}";
        assert VoskJson.fixDecimals(dot).equals(dot) : "a valid result must pass unchanged";
    }

    private static void isolation() throws IOException {
        Path vosk = SRC.resolve("compat/vosk");
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                boolean names = text.contains("org.vosk.") || text.contains("org.infinitytwogames.");
                if (!names) continue;
                assert f.startsWith(vosk) && !f.getFileName().toString().equals("VoskCompat.java")
                        : f + " names a Vosk type outside compat/vosk";
            }
        }
    }
}
