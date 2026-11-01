package com.neoalive.tacz_sewv.wing.guidance;

import com.neoalive.tacz_sewv.heli.guidance.ModeSelector.Precedence;

/**
 * Situation to procedure, fixed wing. A pure function of the snapshot's summary fields; first
 * match wins:
 *
 * <pre>
 *  1  leash RETURN                                      -> RTB          ORDERED
 *  2  a destination, far                                -> TRANSIT      ORDERED if under orders, else FREENAV
 *  3  a destination, near                               -> ORBIT        (about it)
 *  4  RU/US with nobody to answer to                    -> PATROL       FREENAV
 *  5                                                    -> ORBIT        (about the hold centre)
 * </pre>
 * Precedence classes are the helicopter's, so the two stacks rank orders the same way. "Far" has
 * hysteresis on the active procedure: a transit runs until it is within {@link #ARRIVE} orbit radii,
 * but an orbit only gives way to a transit past {@link #DEPART} radii, so an aircraft circling a
 * point that drifts a little keeps circling it rather than flicking to a straight leg.
 *
 * <p>Combat and landing procedures join this table in later phases; until then the drive goal only
 * hands the runtime the free-navigation modes, and keeps flying the rest itself.
 */
public final class WingModeSelector {

    public static final double ARRIVE = 1.2, DEPART = 2.5;

    public record Choice(WingProcedureId id, Precedence precedence) {}

    private WingModeSelector() {}

    public static Choice select(WingSituation s) {
        Precedence nav = s.underOrders ? Precedence.ORDERED : Precedence.FREENAV;
        if (s.leash == WingSituation.Leash.RETURN && s.hasAnchor) return new Choice(WingProcedureId.RTB, Precedence.ORDERED);
        if (s.hasDestination) {
            double threshold = (s.active == WingProcedureId.TRANSIT ? ARRIVE : DEPART) * s.orbitRadius;
            return new Choice(s.destDistance > threshold ? WingProcedureId.TRANSIT : WingProcedureId.ORBIT, nav);
        }
        if (s.autonomous && !s.hasAnchor) return new Choice(WingProcedureId.PATROL, Precedence.FREENAV);
        return new Choice(WingProcedureId.ORBIT, nav);
    }
}
