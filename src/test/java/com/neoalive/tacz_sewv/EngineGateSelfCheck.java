package com.neoalive.tacz_sewv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Fails if any {@code src/main} source still gates airframes with
 * {@code getEngineInfo() instanceof …Helicopter} (planes subclass Helicopter; field null until travel).
 * Run with {@code ./gradlew selfCheck}.
 */
public final class EngineGateSelfCheck {

    private static final Pattern FORBIDDEN = Pattern.compile(
            "getEngineInfo\\(\\)\\s*instanceof\\s+[\\w.]*Helicopter");

    public static void main(String[] args) throws IOException {
        Path root = Path.of("src/main/java");
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("expected src/main/java relative to cwd, got " + Path.of("").toAbsolutePath());
        }
        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    // Comments documenting the trap are allowed.
                    String[] lines = text.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i];
                        String trimmed = line.trim();
                        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                            continue;
                        }
                        if (FORBIDDEN.matcher(line).find()) {
                            hits.add(root.relativize(p) + ":" + (i + 1) + ": " + trimmed);
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        if (!hits.isEmpty()) {
            System.err.println("Forbidden getEngineInfo() instanceof Helicopter gates:");
            hits.forEach(System.err::println);
            throw new AssertionError(hits.size() + " forbidden engine-info gate(s)");
        }
        System.out.println("engine-gate self-check: OK");
    }
}
