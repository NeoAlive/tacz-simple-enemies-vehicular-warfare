package com.neoalive.tacz_sewv.client.voice;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The spoken phrase table for one language ({@code assets/tacz_sewv/voice/<lang>.json}). Pure: no game or
 * Vosk types, so the self-check runs it headless. The recognizer grammar is built from this same table, so
 * a phrase the parser knows is always a phrase the recognizer can hear.
 *
 * <p>Every phrase is stored as its token list. {@code orders} maps a wheel pipeline id to its phrasings,
 * {@code scopes} maps a scope token ({@code ALL}, {@code SELECTED}, {@code KIND:<kinds>},
 * {@code PLATOON:<colour>}) to its phrasings, {@code where} marks a positional order as aimed at the
 * crosshair, and {@code fillers} exist only so the recognizer has somewhere to put them.
 */
public record VoicePhrases(Map<String, List<List<String>>> orders, Map<String, List<List<String>>> scopes,
                           List<List<String>> where, List<String> fillers) {

    public static final String DEFAULT_LANG = "en_us";
    private static final String ROOT = "/assets/tacz_sewv/voice/";

    /** The table for {@code lang}, else English. Null only if even English is missing or broken. */
    @Nullable
    public static VoicePhrases load(String lang) {
        String code = lang.toLowerCase(Locale.ROOT);
        VoicePhrases table = loadExact(code);
        if (table == null) {
            String family = FAMILY.get(code.split("_")[0]);
            if (family != null) table = loadExact(family);
        }
        return table != null ? table : loadExact(DEFAULT_LANG);
    }

    /**
     * The table for a language family: a game locale without a table of its own ({@code es_mx},
     * {@code pt_pt}) and a model's language both land here.
     */
    public static final Map<String, String> FAMILY = Map.of(
            "en", "en_us", "it", "it_it", "de", "de_de", "fr", "fr_fr", "es", "es_es",
            "pt", "pt_br", "pl", "pl_pl", "ru", "ru_ru", "zh", "zh_cn");

    /** Model language codes that are not ISO 639-1. */
    private static final Map<String, String> MODEL_CODES = Map.of("cn", "zh");

    /**
     * The table for a Vosk model, read off its name ({@code vosk-model-small-it-0.22} is Italian), or null
     * when no table matches. The table must follow the model, not the game: a model only knows its own
     * language's words, so Italian phrases on an English model are words it cannot hear.
     */
    @Nullable
    public static String langOfModel(@Nullable String modelName) {
        if (modelName == null) return null;
        String name = modelName.toLowerCase(Locale.ROOT);
        if (!name.startsWith("vosk-model-")) return null;
        name = name.substring("vosk-model-".length());
        if (name.startsWith("small-")) name = name.substring("small-".length());
        String code = name.split("-")[0];
        code = MODEL_CODES.getOrDefault(code, code);
        return FAMILY.get(code);
    }

    @Nullable
    private static VoicePhrases loadExact(String lang) {
        try (InputStream in = VoicePhrases.class.getResourceAsStream(ROOT + lang + ".json")) {
            if (in == null) return null;
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    public static VoicePhrases parse(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        return new VoicePhrases(table(root.getAsJsonObject("orders")), table(root.getAsJsonObject("scopes")),
                phrases(root.getAsJsonArray("where")), List.copyOf(words(root.getAsJsonArray("fillers"))));
    }

    private static Map<String, List<List<String>>> table(@Nullable JsonObject obj) {
        Map<String, List<List<String>>> out = new LinkedHashMap<>();
        if (obj == null) return out;
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            out.put(e.getKey(), phrases(e.getValue().getAsJsonArray()));
        }
        return out;
    }

    private static List<List<String>> phrases(@Nullable JsonArray arr) {
        List<List<String>> out = new ArrayList<>();
        if (arr == null) return out;
        for (JsonElement e : arr) {
            List<String> tokens = tokens(e.getAsString());
            if (!tokens.isEmpty()) out.add(tokens);
        }
        return out;
    }

    private static List<String> words(@Nullable JsonArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        for (JsonElement e : arr) out.addAll(tokens(e.getAsString()));
        return out;
    }

    /** Lower case, letters and digits only, split on anything else. Vosk's unknown-word marker is dropped. */
    public static List<String> tokens(String text) {
        String clean = text.toLowerCase(Locale.ROOT).replace("[unk]", " ").replaceAll("[^\\p{L}\\p{N}']+", " ").trim();
        if (clean.isEmpty()) return List.of();
        return List.of(clean.split(" "));
    }

    /** Every distinct word the table uses, for the recognizer grammar. */
    public Set<String> grammarWords() {
        Set<String> out = new LinkedHashSet<>();
        for (List<List<String>> ps : this.orders.values()) ps.forEach(out::addAll);
        for (List<List<String>> ps : this.scopes.values()) ps.forEach(out::addAll);
        this.where.forEach(out::addAll);
        out.addAll(this.fillers);
        return out;
    }

    /** The Vosk grammar JSON: every word plus {@code [unk]}, so speech outside it is not forced onto an order. */
    public String grammarJson() {
        JsonArray arr = new JsonArray();
        for (String w : grammarWords()) arr.add(w);
        arr.add("[unk]");
        return arr.toString();
    }
}
