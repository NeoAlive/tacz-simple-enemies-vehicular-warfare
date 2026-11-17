package com.neoalive.tacz_sewv.wing.guidance;

import com.neoalive.tacz_sewv.heli.guidance.ModeSelector.Precedence;

/**
 * Situation to procedure, fixed wing. A pure function of the snapshot's summary fields; first
 * match wins:
 *
 * <pre>
 *  1  leash RETURN (incl. the hard range limit)         -> RTB          ORDERED
 *  c  a target or grid mark, not under orders:                          ATTACK
 *       recalled: finish a run in progress (BREAK keeps it), else       -> RTB ORDERED
 *       an attack run is active (it runs to completion; then break)     -> BREAK
 *       out of the engage bubble, not lined up, or the run is unsafe    -> INGRESS
 *       otherwise: mark -> ATTACK_MARK, bomb -> ATTACK_LEVEL, else      -> ATTACK_DIVE
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
 * <p>The attack procedures and BREAK are sticky in the stack, so "choose BREAK while a run is
 * active" means "break once the run completes", and choosing the next combat row while BREAK is
 * active means "go again once the break completes": completion lives in the procedures, where the
 * geometry is. Landing procedures join this table in a later phase.
 */
public final class WingModeSelector {

    public static final double ARRIVE = 1.2, DEPART = 2.5;

    public record Choice(WingProcedureId id, Precedence precedence) {}

    private WingModeSelector() {}

    public static boolean isRun(WingProcedureId id) {
        return id == WingProcedureId.ATTACK_DIVE || id == WingProcedureId.ATTACK_LEVEL || id == WingProcedureId.ATTACK_MARK;
    }

    public static Choice select(WingSituation s) {
        Precedence nav = s.underOrders ? Precedence.ORDERED : Precedence.FREENAV;
        if (s.leash == WingSituation.Leash.RETURN && s.hasAnchor) return new Choice(WingProcedureId.RTB, Precedence.ORDERED);
        if (s.targetValid && !s.underOrders) {
            boolean inRun = isRun(s.active);
            if (s.leash == WingSituation.Leash.RECALL && s.hasAnchor) {
                return inRun ? new Choice(WingProcedureId.BREAK, Precedence.ATTACK)
                        : new Choice(WingProcedureId.RTB, Precedence.ORDERED);
            }
            if (inRun) return new Choice(WingProcedureId.BREAK, Precedence.ATTACK);
            if (s.targetDistance > s.engageRange || !s.established || !s.runSafe) {
                return new Choice(WingProcedureId.INGRESS, Precedence.ATTACK);
            }
            WingProcedureId run = s.isMark ? WingProcedureId.ATTACK_MARK
                    : s.levelDelivery ? WingProcedureId.ATTACK_LEVEL : WingProcedureId.ATTACK_DIVE;
            return new Choice(run, Precedence.ATTACK);
        }
        if (s.hasDestination) {
            double threshold = (s.active == WingProcedureId.TRANSIT ? ARRIVE : DEPART) * s.orbitRadius;
            return new Choice(s.destDistance > threshold ? WingProcedureId.TRANSIT : WingProcedureId.ORBIT, nav);
        }
        if (s.autonomous && !s.hasAnchor) return new Choice(WingProcedureId.PATROL, Precedence.FREENAV);
        return new Choice(WingProcedureId.ORBIT, nav);
    }
}
