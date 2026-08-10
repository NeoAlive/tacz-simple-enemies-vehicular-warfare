package com.neoalive.tacz_sewv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Asserts ModSounds pool counts, sounds.json object entries, and .ogg files agree.
 * Run with {@code ./gradlew selfCheck}.
 */
public final class SoundPoolSelfCheck {

    private static final Pattern POOL = Pattern.compile(
            "pool\\(\"([a-z0-9_]+)\",\\s*(\\d+)\\)");
    private static final Pattern SINGLE = Pattern.compile(
            "register\\(\"([a-z0-9_]+)\"\\)");

    public static void main(String[] args) throws IOException {
        Path modSounds = Path.of("src/main/java/com/neoalive/tacz_sewv/init/ModSounds.java");
        Path soundsJson = Path.of("src/main/resources/assets/tacz_sewv/sounds.json");
        Path soundsDir = Path.of("src/main/resources/assets/tacz_sewv/sounds");
        if (!Files.isRegularFile(modSounds) || !Files.isRegularFile(soundsJson)) {
            throw new IllegalStateException("missing ModSounds.java or sounds.json");
        }

        String src = Files.readString(modSounds, StandardCharsets.UTF_8);
        List<String> problems = new ArrayList<>();
        JsonObject json = JsonParser.parseString(Files.readString(soundsJson, StandardCharsets.UTF_8)).getAsJsonObject();

        Matcher m = POOL.matcher(src);
        while (m.find()) {
            String prefix = m.group(1);
            int count = Integer.parseInt(m.group(2));
            for (int i = 1; i <= count; i++) {
                checkKey(json, soundsDir, problems, prefix + "_" + i);
            }
        }

        Matcher singles = SINGLE.matcher(src);
        while (singles.find()) {
            String key = singles.group(1);
            // pool() also calls register(prefix_i) — those are already covered above.
            if (key.matches(".*_\\d+$")) continue;
            checkKey(json, soundsDir, problems, key);
        }

        for (var e : json.entrySet()) {
            if (!e.getValue().isJsonObject()) {
                problems.add("sounds.json entry " + e.getKey() + " is not a JsonObject (comments are illegal)");
            }
        }

        if (!problems.isEmpty()) {
            problems.forEach(System.err::println);
            throw new AssertionError(problems.size() + " sound pool problem(s)");
        }
        System.out.println("sound-pool self-check: OK");
    }

    private static void checkKey(JsonObject json, Path soundsDir, List<String> problems, String key)
            throws IOException {
        JsonElement entry = json.get(key);
        if (entry == null) {
            problems.add("sounds.json missing key " + key);
            return;
        }
        if (!entry.isJsonObject()) {
            problems.add("sounds.json entry " + key + " must be a JsonObject");
            return;
        }
        Path ogg = findOgg(soundsDir, key);
        if (ogg == null) {
            problems.add("missing .ogg for " + key);
        }
    }

    private static Path findOgg(Path soundsDir, String key) throws IOException {
        if (!Files.isDirectory(soundsDir)) return null;
        try (var walk = Files.walk(soundsDir)) {
            return walk.filter(p -> p.getFileName().toString().equals(key + ".ogg")).findFirst().orElse(null);
        }
    }
}
