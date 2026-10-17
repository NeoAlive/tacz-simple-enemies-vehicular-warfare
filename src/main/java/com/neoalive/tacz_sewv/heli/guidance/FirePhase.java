package com.neoalive.tacz_sewv.heli.guidance;

/**
 * Where a procedure is in an attack, for the run-phase overlay and the scan goals' "committed to a
 * run" test. Only FireRun walks INGRESS, ATTACK, BREAK, REPOSITION; everything else is NONE.
 */
public enum FirePhase {
    NONE, INGRESS, ATTACK, BREAK, REPOSITION
}
