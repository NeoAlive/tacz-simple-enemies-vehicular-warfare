package com.neoalive.tacz_sewv.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Server code never asks {@code Level.hasChunk} / {@code hasChunkAt} whether a chunk is loaded: on the
 * server that is true for a chunk still loading, and the read it guards then blocks the main
 * thread. {@link LoadedTerrain} is the only allowed answer. Client code is exempt.
 */
public final class LoadedTerrainSelfCheck {

    private static final Pattern BANNED = Pattern.compile("\\.hasChunk(?:At)?\\(");

    public static void main(String[] args) throws IOException {
        Path root = Path.of("src/main/java");
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("expected src/main/java relative to cwd, got " + Path.of("").toAbsolutePath());
        }
        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                if (rel.contains("/client/") || rel.endsWith("util/LoadedTerrain.java")) continue;
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.trim().startsWith("*") || line.trim().startsWith("//")) continue;
                    if (BANNED.matcher(line).find()) hits.add(rel + ":" + (i + 1));
                }
            }
        }
        if (!hits.isEmpty()) {
            throw new AssertionError("hasChunk used as a loaded test outside LoadedTerrain: " + hits);
        }
        System.out.println("loaded terrain self-check: OK");
    }
}
