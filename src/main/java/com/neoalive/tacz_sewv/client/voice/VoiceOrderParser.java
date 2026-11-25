package com.neoalive.tacz_sewv.client.voice;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Heard text to an order. Pure, so {@code selfCheckVoice} pins it.
 *
 * <p>The order is found first, by the longest phrase anywhere in the utterance, so "artillery strike" is a
 * fire mission and not the artillery scope followed by noise. The scope is then the longest scope phrase
 * among the words the order did not use. Two different orders matching at the same length is refused
 * rather than guessed. Aim is read off every word, so "go there" both moves and is aimed.
 */
public final class VoiceOrderParser {

    public enum Status { OK, EMPTY, UNKNOWN, AMBIGUOUS }

    /** {@code scope} is null when none was said: the order then goes where the wheel's would. */
    public record Result(Status status, @Nullable String pipelineId, @Nullable String scope, boolean aimed) {
        static Result of(Status status) {
            return new Result(status, null, null, false);
        }
    }

    private VoiceOrderParser() {}

    public static Result parse(VoicePhrases table, String heard) {
        List<String> tokens = VoicePhrases.tokens(heard);
        if (tokens.isEmpty()) return Result.of(Status.EMPTY);

        Match order = longest(table.orders(), tokens, null);
        if (order == null) return Result.of(Status.UNKNOWN);
        if (order.ambiguous) return Result.of(Status.AMBIGUOUS);

        boolean[] used = new boolean[tokens.size()];
        for (int i = order.start; i < order.start + order.length; i++) used[i] = true;
        Match scope = longest(table.scopes(), tokens, used);
        String scopeKey = scope == null || scope.ambiguous ? null : scope.key;

        boolean aimed = false;
        for (List<String> w : table.where()) {
            if (indexOf(tokens, w, null) >= 0) {
                aimed = true;
                break;
            }
        }
        return new Result(Status.OK, order.key, scopeKey, aimed);
    }

    private record Match(String key, int start, int length, boolean ambiguous) {}

    @Nullable
    private static Match longest(Map<String, List<List<String>>> table, List<String> tokens, @Nullable boolean[] used) {
        Match best = null;
        for (Map.Entry<String, List<List<String>>> e : table.entrySet()) {
            for (List<String> phrase : e.getValue()) {
                int at = indexOf(tokens, phrase, used);
                if (at < 0) continue;
                int len = phrase.size();
                if (best == null || len > best.length) {
                    best = new Match(e.getKey(), at, len, false);
                } else if (len == best.length && !best.key.equals(e.getKey())) {
                    best = new Match(best.key, best.start, best.length, true);
                }
            }
        }
        return best;
    }

    /** First start of {@code phrase} in {@code tokens} over words not yet used, or -1. */
    static int indexOf(List<String> tokens, List<String> phrase, @Nullable boolean[] used) {
        int n = tokens.size(), m = phrase.size();
        outer:
        for (int i = 0; i + m <= n; i++) {
            for (int j = 0; j < m; j++) {
                if ((used != null && used[i + j]) || !tokens.get(i + j).equals(phrase.get(j))) continue outer;
            }
            return i;
        }
        return -1;
    }
}
