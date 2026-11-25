package com.neoalive.tacz_sewv.client.voice;

import java.util.Locale;
import java.util.Map;

/**
 * The small Vosk model setup offers for each phrase table. Small models only, because they are the ones
 * that honour a grammar. Names and URLs are Vosk's own (its {@code model-list.json}). Pure, pinned by
 * {@code selfCheckVoice}: every table has a model, and each model's name reads back as that table.
 */
public final class VoiceModels {

    private static final String BASE = "https://alphacephei.com/vosk/models/";

    /** Phrase table to model name. */
    public static final Map<String, String> BY_TABLE = Map.of(
            "en_us", "vosk-model-small-en-us-0.15",
            "it_it", "vosk-model-small-it-0.22",
            "de_de", "vosk-model-small-de-0.15",
            "fr_fr", "vosk-model-small-fr-0.22",
            "es_es", "vosk-model-small-es-0.42",
            "pt_br", "vosk-model-small-pt-0.3",
            "pl_pl", "vosk-model-small-pl-0.22",
            "ru_ru", "vosk-model-small-ru-0.22",
            "zh_cn", "vosk-model-small-cn-0.22");

    /** Download size in MiB, for the consent text. */
    public static final Map<String, Integer> SIZE_MIB = Map.of(
            "vosk-model-small-en-us-0.15", 40, "vosk-model-small-it-0.22", 48, "vosk-model-small-de-0.15", 45,
            "vosk-model-small-fr-0.22", 41, "vosk-model-small-es-0.42", 38, "vosk-model-small-pt-0.3", 31,
            "vosk-model-small-pl-0.22", 51, "vosk-model-small-ru-0.22", 45, "vosk-model-small-cn-0.22", 42);

    private VoiceModels() {}

    /** The model for a game language: its family's table, English when there is none. */
    public static String forGameLanguage(String lang) {
        String code = lang.toLowerCase(Locale.ROOT);
        String table = BY_TABLE.containsKey(code) ? code : VoicePhrases.FAMILY.get(code.split("_")[0]);
        return BY_TABLE.getOrDefault(table == null ? VoicePhrases.DEFAULT_LANG : table, BY_TABLE.get(VoicePhrases.DEFAULT_LANG));
    }

    public static String url(String model) {
        return BASE + model + ".zip";
    }

    public static int sizeMib(String model) {
        return SIZE_MIB.getOrDefault(model, 50);
    }
}
