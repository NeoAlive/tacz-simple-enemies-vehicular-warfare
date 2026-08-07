package com.neoalive.tacz_sewv.entity.ai.command;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.neoalive.tacz_sewv.entity.ai.utility.Signal;

/**
 * Per-driver Stage-4 role published by {@link CommandCoordinator} for the crew brain to read.
 *
 * <p>Same ownership shape as {@code Facts}: looked up by unit network id, written only by the
 * coordinator, never by the drive goal. The scorer raises {@code TASKED_*} from this; it does not
 * invent doctrine branches.
 */
public final class CrewAssignment {

    private static final ConcurrentHashMap<Integer, Snapshot> LIVE = new ConcurrentHashMap<>();

    private CrewAssignment() {}

    /**
     * Immutable view of one driver's assignment. {@code flankSide} is set only for a flank
     * maneuver; {@code priorityTargetId} is soft focus-fire (may be stale — readers re-validate).
     */
    public record Snapshot(
            Assignment.Role role,
            @Nullable Assignment.FlankSide flankSide,
            @Nullable Integer priorityTargetId
    ) {}

    public static void publish(Assignment a) {
        LIVE.put(a.unitId, new Snapshot(a.role, a.flankSide, a.priorityTargetId));
    }

    public static void clear(int unitId) {
        LIVE.remove(unitId);
    }

    public static void clearAll() {
        LIVE.clear();
    }

    /** Drop every id not in {@code keep} — call after a full multi-level command pass. */
    public static void retainAll(Set<Integer> keep) {
        LIVE.keySet().retainAll(keep);
    }

    @Nullable
    public static Snapshot of(int unitId) {
        return LIVE.get(unitId);
    }

    /**
     * Raise exactly one {@code TASKED_*} signal for the driver's current role. Role → signal:
     * BoF → {@link Signal#TASKED_BASE_OF_FIRE}; maneuver with a flank side →
     * {@link Signal#TASKED_FLANK}; maneuver without → {@link Signal#TASKED_ADVANCE}; overwatch /
     * reserve / hold → {@link Signal#TASKED_HOLD}; withdraw → {@link Signal#TASKED_WITHDRAW}.
     */
    public static void raiseTaskSignals(int unitId, double[] signals) {
        Snapshot s = LIVE.get(unitId);
        if (s == null) return;
        switch (s.role) {
            case BASE_OF_FIRE -> signals[Signal.TASKED_BASE_OF_FIRE.ordinal()] = 1.0;
            case MANEUVER -> {
                if (s.flankSide != null) {
                    signals[Signal.TASKED_FLANK.ordinal()] = 1.0;
                } else {
                    signals[Signal.TASKED_ADVANCE.ordinal()] = 1.0;
                }
            }
            case OVERWATCH, RESERVE, HOLD -> signals[Signal.TASKED_HOLD.ordinal()] = 1.0;
            case WITHDRAW -> signals[Signal.TASKED_WITHDRAW.ordinal()] = 1.0;
        }
    }

    /**
     * Flank side when this driver is a flank maneuver element; otherwise null (caller keeps
     * id-parity default).
     */
    @Nullable
    public static Assignment.FlankSide taskedFlankSide(int unitId) {
        Snapshot s = LIVE.get(unitId);
        if (s == null || s.role != Assignment.Role.MANEUVER) return null;
        return s.flankSide;
    }

    @Nullable
    public static Integer priorityTargetId(int unitId) {
        Snapshot s = LIVE.get(unitId);
        return s == null ? null : s.priorityTargetId;
    }
}
