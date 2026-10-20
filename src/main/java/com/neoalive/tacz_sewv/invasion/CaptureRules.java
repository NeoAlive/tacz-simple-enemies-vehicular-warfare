package com.neoalive.tacz_sewv.invasion;

import java.util.Set;

/**
 * The capture decision, free of any world state so it can be checked headless
 * ({@code ./gradlew selfCheckInvasion}). Its input is the set of teams that count inside the zone:
 * players, ground crews and capture-on infantry. Aircraft and capture-off infantry are dropped by
 * the scan before this runs — they neither take a zone nor stall one.
 */
public final class CaptureRules {

    private CaptureRules() {}

    public enum Outcome {
        /** Nobody counting present: partial progress drops to zero. */
        RESET,
        /** Two or more teams present: progress frozen. */
        CONTESTED,
        /** Only the holder is present: nothing to take. */
        HELD,
        /** One non-holding team present: progress advances for {@link Step#team}. */
        ADVANCE
    }

    public record Step(Outcome outcome, String team) {}

    public static Step step(String holder, Set<String> present) {
        if (present.isEmpty()) return new Step(Outcome.RESET, "");
        if (present.size() >= 2) return new Step(Outcome.CONTESTED, "");
        String sole = present.iterator().next();
        if (!holder.isEmpty() && sole.equals(holder)) return new Step(Outcome.HELD, sole);
        return new Step(Outcome.ADVANCE, sole);
    }
}
