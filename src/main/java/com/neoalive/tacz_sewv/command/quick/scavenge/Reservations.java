package com.neoalive.tacz_sewv.command.quick.scavenge;

import java.util.HashMap;
import java.util.Map;

/**
 * What each unit of a run is on its way to take, per source and item. A source is only worth a
 * walk for what is left after everyone already heading there, so a group spreads by availability.
 */
final class Reservations {

    private final Map<Object, Map<String, Integer>> bySource = new HashMap<>();

    int reserved(Object source, String key) {
        Map<String, Integer> m = this.bySource.get(source);
        return m == null ? 0 : m.getOrDefault(key, 0);
    }

    boolean any(Object source) {
        return this.bySource.containsKey(source);
    }

    void add(Object source, Map<String, Integer> plan) {
        if (plan.isEmpty()) return;
        Map<String, Integer> m = this.bySource.computeIfAbsent(source, s -> new HashMap<>());
        plan.forEach((k, v) -> m.merge(k, v, Integer::sum));
    }

    void remove(Object source, Map<String, Integer> plan) {
        Map<String, Integer> m = this.bySource.get(source);
        if (m == null) return;
        plan.forEach((k, v) -> m.computeIfPresent(k, (kk, old) -> old <= v ? null : old - v));
        if (m.isEmpty()) this.bySource.remove(source);
    }
}
