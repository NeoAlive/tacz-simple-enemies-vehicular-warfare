package com.neoalive.tacz_sewv.heli.guidance;

/**
 * Situation to procedure (plan section 4.11). A pure function of the snapshot's summary fields;
 * it never reads the world, and never the geometry half of the snapshot.
 *
 * <p>Phase 2 subset, first match wins:
 * <ol>
 * <li>LANDED -> PARK, LAND -> LAND, RAPPEL -> RAPPEL_HOLD, TAKEOFF -> TAKEOFF</li>
 * <li>under a player order: far from the destination -> TRANSIT, else HOVER_HOLD</li>
 * <li>a live target -> HOVER_HOLD (placeholder for the attack rows, which arrive with the DetNav
 *     procedures in Phase 3; the fire assist still shoots from the hover)</li>
 * <li>far from a destination -> TRANSIT</li>
 * <li>HOVER_HOLD (always feasible, so a winner always exists)</li>
 * </ol>
 * "Far" has hysteresis on the active procedure: a transit runs until it arrives
 * ({@link Procedures#ARRIVE_RADIUS}), but a hover only gives way to a transit past
 * {@link #TRANSIT_MIN}; closer than that the hold point itself moves (rate-limited).
 */
public final class ModeSelector {

    public static final double TRANSIT_MIN = 48.0;

    private ModeSelector() {}

    public static ProcedureId select(Situation s) {
        switch (s.order) {
            case LANDED:
                return ProcedureId.PARK;
            case LAND:
                return ProcedureId.LAND;
            case RAPPEL:
                return ProcedureId.RAPPEL_HOLD;
            case TAKEOFF:
                return ProcedureId.TAKEOFF;
            default:
                break;
        }
        double threshold = s.active == ProcedureId.TRANSIT ? Procedures.ARRIVE_RADIUS : TRANSIT_MIN;
        boolean far = s.hasDestination && s.destDistance > threshold;
        if (s.underOrders) return far ? ProcedureId.TRANSIT : ProcedureId.HOVER_HOLD;
        if (s.targetValid) return ProcedureId.HOVER_HOLD;
        return far ? ProcedureId.TRANSIT : ProcedureId.HOVER_HOLD;
    }
}
