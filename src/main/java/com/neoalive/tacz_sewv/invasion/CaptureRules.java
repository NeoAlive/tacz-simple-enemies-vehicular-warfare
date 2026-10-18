package com.neoalive.tacz_sewv.invasion;

import java.util.HashSet;
import java.util.Set;

/**
 * The capture decision, free of any world state so it can be checked headless
 * ({@code ./gradlew selfCheckInvasion}).
 * <p>
 * Presence comes in two sets. A <b>capturer</b> (a player, ground crew, infantry with capture on)
 * can take a zone; a <b>contester</b> (infantry with capture off) can only hold one: it blocks an
 * enemy capture but never advances its own. Aircraft are in neither — the scan drops them before
 * this runs. Every team in either set is "present".
 */
public final class CaptureRules {

    private CaptureRules() {}

    public enum Outcome {
        /** Nobody present: partial progress drops to zero. */
        RESET,
        /** Two or more teams present: progress frozen. */
        CONTESTED,
        /** Only the holder is present: nothing to take. */
        HELD,
        /** One non-holding team present, but none of it can capture: progress frozen. */
        FROZEN,
        /** One non-holding team with a capturer present: progress advances for {@link Step#team}. */
        ADVANCE
    }

    public record Step(Outcome outcome, String team) {}

    public static Step step(String holder, Set<String> capturers, Set<String> contesters) {
        Set<String> present = new HashSet<>(capturers);
        present.addAll(contesters);
        if (present.isEmpty()) return new Step(Outcome.RESET, "");
        if (present.size() >= 2) return new Step(Outcome.CONTESTED, "");
        String sole = present.iterator().next();
        if (!holder.isEmpty() && sole.equals(holder)) return new Step(Outcome.HELD, sole);
        if (!capturers.contains(sole)) return new Step(Outcome.FROZEN, sole);
        return new Step(Outcome.ADVANCE, sole);
    }
}
