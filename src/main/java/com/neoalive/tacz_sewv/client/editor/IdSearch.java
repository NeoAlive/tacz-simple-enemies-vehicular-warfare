package com.neoalive.tacz_sewv.client.editor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Autofill matching shared by the pool editors: every whitespace-separated token of the query must
 * appear in the id or its display name. Ranked id-prefix, then path-prefix, then name-word prefix,
 * then plain substring; shorter ids first within a rank — the same order the loadout catalog uses.
 */
public final class IdSearch {

    private IdSearch() {}

    public static List<String> match(String typed, Collection<String> pool, Function<String, String> label,
                                     int limit) {
        String q = typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return pool.stream().limit(limit).toList();
        List<String> tokens = Arrays.asList(q.split("\\s+"));
        String first = tokens.get(0);
        record Scored(String id, int score) {}
        List<Scored> hits = new ArrayList<>();
        for (String id : pool) {
            String lower = id.toLowerCase(Locale.ROOT);
            String hay = lower + " " + label.apply(id).toLowerCase(Locale.ROOT);
            if (!tokens.stream().allMatch(hay::contains)) continue;
            int colon = lower.indexOf(':');
            int score;
            if (lower.startsWith(first)) score = 0;
            else if (colon >= 0 && lower.startsWith(first, colon + 1)) score = 1;
            else if (hay.contains(" " + first)) score = 2;
            else score = 3;
            hits.add(new Scored(id, score));
        }
        hits.sort(Comparator.comparingInt(Scored::score).thenComparingInt(s -> s.id().length())
                .thenComparing(Scored::id));
        return hits.stream().limit(limit).map(Scored::id).toList();
    }
}
