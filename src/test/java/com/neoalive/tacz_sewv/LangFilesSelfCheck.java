package com.neoalive.tacz_sewv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Locale files match en_us: same keys, same placeholders, same typed tokens, no padded phrasing.
 */
public final class LangFilesSelfCheck {

    private static final List<String> LOCALES = List.of(
            "it_it", "de_de", "es_es", "es_mx", "ru_ru", "zh_cn");

    private static final String GLYPHS = "➤⚑■⚡⊘⌖▲▼⚠◯⚔✸⛨⌂↻⚐↩✕⏏☂◎⇉✎↔★≈±";
    private static final String FORBIDDEN = "—–…→←↔⇒⟶⟵➔➜►▶▸➡⟹⇐↑↓⇑⇓";

    private static final Pattern SPEC = Pattern.compile("%(?:(\\d+)\\$)?(?:\\.\\d+)?([sdif])");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern TECHNICAL = Pattern.compile(
            "/sewv\\b\\S*|/superbwarfare\\b\\S*|/reload\\b|/team_base\\b|/team\\b|config/[\\w./-]+"
                    + "|\\b[a-z]+(?:[A-Z][A-Za-z0-9]*)+\\b|\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b");
    private static final Pattern ACRONYM = Pattern.compile(
            "\\b(?:SBW|PMC|FOB|RTS|TOWs?|CAS|IFVs?|SEM|SEWV|CFG|BURST|PACK|RU|US|TaCZ|TACZ|OP|FE|AI|NPC|HUD|Shift|Ctrl|Alt|Esc)\\b");

    private static final String[] PHRASES = {
            "Simple Enemy Mod Config",
            "Simple Enemy Mod",
            "Simple Enemy",
            "Superb Warfare",
            "SuperbWarfare",
            "Open Parties and Claims",
            "OpenPAC",
            "Limitless Vehicles",
            "SEM Extended",
            "Xaero's World Map",
            "Xaero's Minimap",
            "Xaero",
            "berezka",
            "Corpse"
    };

    private static final Map<String, String[]> EXTRA = Map.of(
            "command.tacz_sewv.diplomacy.bad_relation", new String[]{"\\bally\\b", "\\benemy\\b"},
            "command.tacz_sewv.diplomacy.bad_relation_pair", new String[]{"\\bally\\b", "\\benemy\\b", "\\bneutral\\b"},
            "command.tacz_sewv.diplomacy.bad_kind", new String[]{"\\bplayer\\b", "\\bfaction\\b", "\\bside\\b"},
            "command.tacz_sewv.diplomacy.unknown_side", new String[]{"\\bru\\b", "\\bus\\b"},
            "command.tacz_sewv.zones.bad_type", new String[]{"\\bprioritization\\b", "\\bnozone\\b"},
            "command.tacz_sewv.zones.bad_scope", new String[]{"\\ball\\b", "\\bnearby\\b"});

    private static final Map<String, List<String>> BANNED = Map.of(
            "it_it", List.of("è importante", "si prega", "al fine di", "vale la pena", "tieni presente", "assicurati"),
            "de_de", List.of("es ist wichtig", "bitte beachten", "darüber hinaus", "nicht zuletzt", "im wesentlichen"),
            "es_es", List.of("es importante", "cabe destacar", "con el fin de", "ten en cuenta", "asegúrate", "asegurate", "simplemente"),
            "es_mx", List.of("es importante", "cabe destacar", "con el fin de", "ten en cuenta", "asegúrate", "asegurate", "simplemente"),
            "ru_ru", List.of("важно отметить", "следует отметить", "данный", "данная", "данное", "данного", "в целях", "обратите внимание"),
            "zh_cn", List.of("值得注意的是", "请注意", "旨在", "简单来说", "为了确保", "进行"));

    private static final Pattern ES_MX_EXTRA = Pattern.compile(
            "\\bvosotros\\b|\\bvosotras\\b|\\bvuestro\\b|\\bvuestra\\b|\\bvuestros\\b|\\bvuestras\\b"
                    + "|\\bcoger\\b|\\bcoges\\b|\\bcoge\\b|\\bcogen\\b|\\bcoged\\b|\\bcogemos\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static void main(String[] args) throws IOException {
        Path dir = Path.of("src/main/resources/assets/tacz_sewv/lang");
        JsonObject en = readObject(dir.resolve("en_us.json"));
        List<String> problems = new ArrayList<>();
        JsonObject es = null;
        JsonObject mx = null;
        for (String locale : LOCALES) {
            Path file = dir.resolve(locale + ".json");
            if (!Files.isRegularFile(file)) {
                problems.add(locale + ": missing file");
                continue;
            }
            JsonObject tr = readObject(file);
            if ("es_es".equals(locale)) {
                es = tr;
            }
            if ("es_mx".equals(locale)) {
                mx = tr;
            }
            checkLocale(locale, en, tr, problems);
        }
        if (es != null && mx != null && Files.readString(dir.resolve("es_es.json")).equals(
                Files.readString(dir.resolve("es_mx.json")))) {
            problems.add("es_es.json and es_mx.json are identical");
        }
        if (!problems.isEmpty()) {
            int shown = Math.min(problems.size(), 80);
            for (int i = 0; i < shown; i++) {
                System.err.println(problems.get(i));
            }
            if (problems.size() > shown) {
                System.err.println("... " + (problems.size() - shown) + " more");
            }
            throw new AssertionError(problems.size() + " lang problem(s)");
        }
        System.out.println("lang self-check: OK (" + en.size() + " keys, " + LOCALES.size() + " locales)");
    }

    private static void checkLocale(String locale, JsonObject en, JsonObject tr, List<String> problems) {
        for (String key : en.keySet()) {
            if (!tr.has(key)) {
                problems.add(locale + " missing " + key);
            }
        }
        for (String key : tr.keySet()) {
            if (!en.has(key)) {
                problems.add(locale + " extra " + key);
            }
        }
        for (var entry : en.entrySet()) {
            String key = entry.getKey();
            if (!tr.has(key) || !tr.get(key).isJsonPrimitive() || !tr.get(key).getAsJsonPrimitive().isString()) {
                if (tr.has(key)) {
                    problems.add(locale + " " + key + ": value is not a string");
                }
                continue;
            }
            String src = entry.getValue().getAsString();
            String dst = tr.get(key).getAsString();
            if (dst.isBlank()) {
                problems.add(locale + " " + key + ": empty");
                continue;
            }
            checkLine(locale, key, src, dst, problems);
        }
    }

    private static void checkLine(String locale, String key, String src, String dst, List<String> problems) {
        String where = locale + " " + key;
        if (count(src, '\n') != count(dst, '\n')) {
            problems.add(where + ": newline count");
        }
        for (int i = 0; i < GLYPHS.length(); ) {
            int cp = GLYPHS.codePointAt(i);
            if (countCp(src, cp) != countCp(dst, cp)) {
                problems.add(where + ": glyph " + new String(Character.toChars(cp)));
            }
            i += Character.charCount(cp);
        }
        for (int i = 0; i < FORBIDDEN.length(); ) {
            int cp = FORBIDDEN.codePointAt(i);
            if (countCp(dst, cp) > countCp(src, cp)) {
                problems.add(where + ": banned mark " + new String(Character.toChars(cp)));
            }
            i += Character.charCount(cp);
        }
        Map<Integer, Character> srcSpecs = specs(src, where + " en", problems);
        Map<Integer, Character> dstSpecs = specs(dst, where, problems);
        if (!srcSpecs.equals(dstSpecs)) {
            problems.add(where + ": placeholders " + srcSpecs + " vs " + dstSpecs);
        }
        List<String> srcNums = numbers(src);
        List<String> dstNums = numbers(dst);
        if (!srcNums.equals(dstNums)) {
            problems.add(where + ": numbers " + srcNums + " vs " + dstNums);
        }
        for (String phrase : PHRASES) {
            if (src.contains(phrase) && !dst.contains(phrase)) {
                problems.add(where + ": dropped " + phrase);
            }
        }
        requirePattern(ACRONYM, src, dst, where, problems);
        requirePattern(TECHNICAL, src, dst, where, problems);
        String[] extra = EXTRA.get(key);
        if (extra != null) {
            for (String token : extra) {
                if (!Pattern.compile(token).matcher(dst).find()) {
                    problems.add(where + ": dropped typed word " + token);
                }
            }
        }
        String folded = dst.toLowerCase();
        for (String phrase : BANNED.getOrDefault(locale, List.of())) {
            if (hasPhrase(dst, folded, phrase)) {
                problems.add(where + ": banned phrase " + phrase);
            }
        }
        if ("es_mx".equals(locale) && ES_MX_EXTRA.matcher(dst).find()) {
            problems.add(where + ": Spain-only wording");
        }
    }

    private static boolean hasPhrase(String raw, String folded, String phrase) {
        boolean han = phrase.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
        if (han) {
            return raw.contains(phrase);
        }
        return Pattern.compile("(?<!\\p{L})" + Pattern.quote(phrase) + "(?!\\p{L})",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(folded).find();
    }

    private static void requirePattern(Pattern pattern, String src, String dst, String where, List<String> problems) {
        Matcher matcher = pattern.matcher(src);
        while (matcher.find()) {
            String token = matcher.group();
            if (!dst.contains(token)) {
                problems.add(where + ": dropped " + token);
            }
        }
    }

    private static Map<Integer, Character> specs(String text, String where, List<String> problems) {
        Map<Integer, Character> out = new TreeMap<>();
        boolean explicit = false;
        boolean implicit = false;
        int auto = 1;
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) != '%') {
                i++;
                continue;
            }
            if (i + 1 < text.length() && text.charAt(i + 1) == '%') {
                i += 2;
                continue;
            }
            Matcher matcher = SPEC.matcher(text.substring(i));
            if (!matcher.lookingAt()) {
                i++;
                continue;
            }
            if (matcher.group(1) != null) {
                explicit = true;
                int index = Integer.parseInt(matcher.group(1));
                out.put(index, matcher.group(2).charAt(0));
            } else {
                implicit = true;
                out.put(auto++, matcher.group(2).charAt(0));
            }
            i += matcher.end();
        }
        if (explicit && implicit) {
            problems.add(where + ": mixed positional placeholders");
        }
        if (explicit) {
            for (int n = 1; n <= out.size(); n++) {
                if (!out.containsKey(n)) {
                    problems.add(where + ": placeholder index gap");
                    break;
                }
            }
        }
        return out;
    }

    private static List<String> numbers(String text) {
        StringBuilder masked = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '%') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '%') {
                    masked.append("  ");
                    i += 2;
                    continue;
                }
                Matcher matcher = SPEC.matcher(text.substring(i));
                if (matcher.lookingAt()) {
                    masked.append(" ".repeat(matcher.end()));
                    i += matcher.end();
                    continue;
                }
            }
            masked.append(text.charAt(i));
            i++;
        }
        List<String> found = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(masked);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        found.sort(null);
        return found;
    }

    private static int count(String text, char ch) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == ch) {
                n++;
            }
        }
        return n;
    }

    private static int countCp(String text, int cp) {
        int n = 0;
        for (int i = 0; i < text.length(); ) {
            int cur = text.codePointAt(i);
            if (cur == cp) {
                n++;
            }
            i += Character.charCount(cur);
        }
        return n;
    }

    private static JsonObject readObject(Path file) throws IOException {
        JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            throw new IllegalStateException(file + " is not a JSON object");
        }
        JsonObject object = parsed.getAsJsonObject();
        for (var entry : object.entrySet()) {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                throw new IllegalStateException(file + " value for " + entry.getKey() + " is not a string");
            }
        }
        return object;
    }

    private LangFilesSelfCheck() {
    }
}
