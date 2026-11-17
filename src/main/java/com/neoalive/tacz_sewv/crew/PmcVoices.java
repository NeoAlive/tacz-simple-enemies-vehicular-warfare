package com.neoalive.tacz_sewv.crew;

import java.util.List;

import org.jetbrains.annotations.Nullable;

/**
 * Crew radio languages a player can give their PMC crews. The key is the pool suffix
 * ({@code <line>_<key>}); {@code ru} reuses the RU faction's own pools. Also the server whitelist.
 */
public final class PmcVoices {

    public static final String DEFAULT = "pmc";

    /** Name is the language's own name; null for the default, which is translated. */
    public record Voice(String key, @Nullable String name) {}

    public static final List<Voice> ALL = List.of(
            new Voice(DEFAULT, null),
            new Voice("pmc_cs", "Čeština"),
            new Voice("pmc_de", "Deutsch"),
            new Voice("pmc_es", "Español"),
            new Voice("pmc_fr", "Français"),
            new Voice("pmc_it", "Italiano"),
            new Voice("pmc_hu", "Magyar"),
            new Voice("pmc_nl", "Nederlands"),
            new Voice("pmc_pl", "Polski"),
            new Voice("pmc_ro", "Română"),
            new Voice("pmc_fi", "Suomi"),
            new Voice("pmc_sv", "Svenska"),
            new Voice("pmc_tr", "Türkçe"),
            new Voice("pmc_el", "Ελληνικά"),
            new Voice("ru", "Русский"),
            new Voice("pmc_uk", "Українська"),
            new Voice("pmc_he", "עברית"),
            new Voice("pmc_th", "ไทย"),
            new Voice("pmc_zh", "中文"),
            new Voice("pmc_ja", "日本語"));

    private PmcVoices() {}

    public static boolean valid(String key) {
        for (Voice v : ALL) {
            if (v.key().equals(key)) return true;
        }
        return false;
    }

    public static String sanitize(@Nullable String key) {
        return key != null && valid(key) ? key : DEFAULT;
    }

    @Nullable
    public static Voice of(String key) {
        for (Voice v : ALL) {
            if (v.key().equals(key)) return v;
        }
        return null;
    }
}
