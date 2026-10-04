package com.neoalive.tacz_sewv.heli.guidance;

/**
 * Situation to procedure (plan section 4.11). A pure function of the snapshot's summary fields;
 * it never reads the world, and never the geometry half of the snapshot. First match wins:
 *
 * <pre>
 *  0    airborne with the engine out                               -> AUTOROTATE   ORDER_FORCED
 *  1-4  LANDED -> PARK, LAND -> LAND, RAPPEL -> RAPPEL_HOLD, TAKEOFF -> TAKEOFF   ORDER_FORCED
 *  5    health < evadeHealth, live target, no order                -> EVADE        SURVIVAL
 *  6    under orders, far from the destination                     -> TRANSIT      ORDERED
 *  7    under orders                                               -> HOVER_HOLD   ORDERED
 *  8    armed, live target, ammunition:                                            ATTACK
 *   a     a firing run is in progress                              -> FIRE_RUN (sticky)
 *   b     no line of sight                                         -> FIRE_LOOP (aspect change)
 *   c     guided weapon, vehicle target, wind and yaw envelopes ok -> FIRE_STILL
 *   d     fast target                                              -> FIRE_RUN
 *   e     otherwise pick8e: FIRE_RUN or FIRE_LOOP
 *  9    live target, unarmed or empty    -> TRANSIT if a destination, else HOVER_HOLD   FREENAV
 *  10   far from a destination                                     -> TRANSIT      FREENAV
 *  11   RU/US with no destination                                  -> PATROL       FREENAV
 *  12                                                              -> HOVER_HOLD   FREENAV
 * </pre>
 * HOVER_HOLD is always feasible, so a winner always exists. "Far" has hysteresis on the active
 * procedure: a transit runs until it arrives ({@link Procedures#ARRIVE_RADIUS}), but a hover only
 * gives way to a transit past {@link #TRANSIT_MIN}; closer than that the hold point moves instead.
 */
public final class ModeSelector {

    public static final double TRANSIT_MIN = 48.0;

    /** Precedence classes, lowest first: a higher class may pre-empt a lower one (plan 4.11). */
    public enum Precedence { FREENAV, ATTACK, ORDERED, SURVIVAL, ORDER_FORCED }

    public record Choice(ProcedureId id, Precedence precedence) {}

    private ModeSelector() {}

    public static Choice select(Situation s) {
        if (s.engineOut) return forced(ProcedureId.AUTOROTATE); // physics outranks every order
        switch (s.order) {
            case LANDED:
                return forced(ProcedureId.PARK);
            case LAND:
                return forced(ProcedureId.LAND);
            case RAPPEL:
                return forced(ProcedureId.RAPPEL_HOLD);
            case TAKEOFF:
                return forced(ProcedureId.TAKEOFF);
            default:
                break;
        }
        if (s.healthFrac < s.evadeHealth && s.targetValid && !s.underOrders) {
            return new Choice(ProcedureId.EVADE, Precedence.SURVIVAL);
        }
        double threshold = s.active == ProcedureId.TRANSIT ? Procedures.ARRIVE_RADIUS : TRANSIT_MIN;
        boolean far = s.hasDestination && s.destDistance > threshold;
        if (s.underOrders) return new Choice(far ? ProcedureId.TRANSIT : ProcedureId.HOVER_HOLD, Precedence.ORDERED);
        if (s.armed && s.targetValid && s.ammoFrac > 0.0) return new Choice(attack(s), Precedence.ATTACK);
        if (s.targetValid) {
            return new Choice(s.hasDestination ? ProcedureId.TRANSIT : ProcedureId.HOVER_HOLD, Precedence.FREENAV);
        }
        if (far) return new Choice(ProcedureId.TRANSIT, Precedence.FREENAV);
        if (s.autonomous && !s.hasDestination) return new Choice(ProcedureId.PATROL, Precedence.FREENAV);
        return new Choice(ProcedureId.HOVER_HOLD, Precedence.FREENAV);
    }

    private static Choice forced(ProcedureId id) {
        return new Choice(id, Precedence.ORDER_FORCED);
    }

    private static ProcedureId attack(Situation s) {
        if (s.inFiringRun) return ProcedureId.FIRE_RUN;
        if (!s.targetLos) return ProcedureId.FIRE_LOOP;
        if (s.weaponGuided && s.targetCategory == Situation.TargetCategory.VEHICLE
                && s.windCeilingOk && s.yawStandoffOk) {
            return ProcedureId.FIRE_STILL;
        }
        if (s.targetMotion == Situation.TargetMotion.FAST) return ProcedureId.FIRE_RUN;
        return pick8e(s.engageCycle, s.pilotId, s.targetId) ? ProcedureId.FIRE_RUN : ProcedureId.FIRE_LOOP;
    }

    /**
     * Row 8e (plan Q2/R1): a deterministic stand-in for a fair coin. Reproducible, about 50/50,
     * different per pilot, no learnable per-pilot pattern. The key is assembled in 64 bits from
     * unsigned fields (an {@code int << 32} would shift by 0 and drop the pilot id), run through one
     * SplitMix64 step (the golden-gamma add keeps key 0 off a fixed point), and bit 63 decides:
     * low bits of a multiply-based mix are the weak ones.
     *
     * @return true for FIRE_RUN, false for FIRE_LOOP
     */
    public static boolean pick8e(int engageCycle, int pilotId, int targetId) {
        long key = Integer.toUnsignedLong(engageCycle) ^ (Integer.toUnsignedLong(pilotId) << 32)
                ^ Integer.toUnsignedLong(targetId);
        long z = key + 0x9E3779B97F4A7C15L;
        z ^= z >>> 30;
        z *= 0xBF58476D1CE4E5B9L;
        z ^= z >>> 27;
        z *= 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z & 0x8000_0000_0000_0000L) != 0;
    }
}
