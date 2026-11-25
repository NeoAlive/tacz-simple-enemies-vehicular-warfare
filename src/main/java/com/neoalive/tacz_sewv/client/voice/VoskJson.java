package com.neoalive.tacz_sewv.client.voice;

import java.util.regex.Pattern;

/**
 * Vosk prints its result numbers with the C library's locale, so on a system whose locale uses a decimal
 * comma (Italian, German, ...) a word reads {@code "conf" : 0,474027,} and the result is not valid JSON.
 * Partial results carry no numbers, which is why a live transcript can show the right words while every
 * final result failed to parse. Pure, pinned by {@code selfCheckVoice}.
 */
public final class VoskJson {

    /** A number value written with a decimal comma: the digits right after a key's colon. */
    private static final Pattern COMMA_DECIMAL = Pattern.compile("(:\\s*-?\\d+),(\\d+)");

    private VoskJson() {}

    public static String fixDecimals(String json) {
        return COMMA_DECIMAL.matcher(json).replaceAll("$1.$2");
    }
}
