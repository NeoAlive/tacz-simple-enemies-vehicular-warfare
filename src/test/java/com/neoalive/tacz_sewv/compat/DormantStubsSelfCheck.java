package com.neoalive.tacz_sewv.compat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * NeoForge port branch: the dormant-integration stubs under {@code src/dormant-stubs/java} stand in
 * for facades excluded from compilation (build.gradle {@code dormantSources}). Run with
 * {@code ./gradlew selfCheck}.
 *
 * <ol>
 *   <li>Each stub declares exactly the public static surface of its real facade (method name +
 *       parameter types, public static fields), ignoring {@code @SubscribeEvent} handlers, which
 *       only the event bus calls. Compilation already fails when core calls something a stub
 *       lacks; this also catches the opposite drift, so a facade grown on main is noticed at the
 *       next merge rather than when someone finally calls it.</li>
 *   <li>No stub imports a type from the mod it stands in for.</li>
 * </ol>
 */
public final class DormantStubsSelfCheck {

    private static final Path STUBS = Path.of("src/dormant-stubs/java");
    private static final Path MAIN = Path.of("src/main/java");

    /** Import prefixes that belong to dormant mods — a stub naming one would defeat the point. */
    private static final List<String> FOREIGN = List.of(
            "xaero.", "org.ywzj.", "de.maxhenkel.", "team.creative.", "com.minecolonies.",
            "com.ldtteam.", "org.berezka.", "me.adda.", "com.norwood.");

    private static final Pattern MEMBER = Pattern.compile(
            "(@SubscribeEvent[^\\n]*\\s+)?public\\s+static\\s+(?:final\\s+)?([\\w.<>\\[\\], ?]+?)\\s+(\\w+)\\s*(\\([^)]*\\)|=)",
            Pattern.MULTILINE);

    public static void main(String[] args) throws IOException {
        if (!Files.isDirectory(STUBS) || !Files.isDirectory(MAIN)) {
            throw new IllegalStateException("expected src/dormant-stubs/java and src/main/java relative to cwd, got "
                    + Path.of("").toAbsolutePath());
        }
        List<String> problems = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> walk = Files.walk(STUBS)) {
            for (Path stub : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                Path rel = STUBS.relativize(stub);
                Path real = MAIN.resolve(rel);
                if (!Files.isRegularFile(real)) {
                    problems.add(rel + ": no real facade at " + real);
                    continue;
                }
                String stubSrc = Files.readString(stub, StandardCharsets.UTF_8);
                String realSrc = Files.readString(real, StandardCharsets.UTF_8);
                for (String line : stubSrc.lines().toList()) {
                    String t = line.strip();
                    if (!t.startsWith("import ")) continue;
                    for (String f : FOREIGN) {
                        if (t.startsWith("import " + f) || t.startsWith("import static " + f)) {
                            problems.add(rel + ": stub imports dormant-mod type: " + t);
                        }
                    }
                }
                Set<String> want = surface(realSrc);
                Set<String> got = surface(stubSrc);
                if (!want.equals(got)) {
                    Set<String> missing = new TreeSet<>(want);
                    missing.removeAll(got);
                    Set<String> extra = new TreeSet<>(got);
                    extra.removeAll(want);
                    problems.add(rel + ": surface drift — missing " + missing + ", extra " + extra);
                }
                checked++;
            }
        }
        if (checked == 0) problems.add("no stubs found under " + STUBS);
        if (!problems.isEmpty()) {
            throw new AssertionError("Dormant stubs out of step:\n  " + String.join("\n  ", problems));
        }
        System.out.println("DormantStubsSelfCheck OK (" + checked + " stubs)");
    }

    /** Public static members, as "name(SimpleType,...)" or "name=" for fields; event handlers skipped. */
    private static Set<String> surface(String src) {
        String noComments = src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        Set<String> out = new TreeSet<>();
        Matcher m = MEMBER.matcher(noComments);
        while (m.find()) {
            if (m.group(1) != null) continue;
            String name = m.group(3);
            String tail = m.group(4);
            if (tail.equals("=")) {
                out.add(name + "=");
                continue;
            }
            List<String> types = new ArrayList<>();
            String params = tail.substring(1, tail.length() - 1).replaceAll("@\\w+(\\([^)]*\\))?", " ").strip();
            if (!params.isEmpty()) {
                for (String p : splitTopLevel(params)) {
                    String decl = p.strip().replaceAll("\\bfinal\\s+", "");
                    int sp = decl.lastIndexOf(' ');
                    String type = sp < 0 ? decl : decl.substring(0, sp).strip();
                    type = type.replaceAll("\\s+", "");
                    int dot = type.lastIndexOf('.', type.indexOf('<') < 0 ? type.length() : type.indexOf('<'));
                    types.add(dot < 0 ? type : type.substring(dot + 1));
                }
            }
            out.add(name + "(" + String.join(",", types) + ")");
        }
        return out;
    }

    private static List<String> splitTopLevel(String params) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(params.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(params.substring(start));
        return parts;
    }
}
