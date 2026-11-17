package com.neoalive.tacz_sewv.wing.guidance;

/**
 * Stable fixed-wing procedure ids. Append only: ordinals are logged and may be persisted.
 * Phase 3 implements the free-navigation set; the combat and landing ids are reserved here so the
 * order never has to change when they land.
 */
public enum WingProcedureId {
    SAFETY_HOLD, TRANSIT, ORBIT, PATROL, RTB, CLIMBOUT,
    INGRESS, ATTACK_DIVE, ATTACK_LEVEL, ATTACK_MARK, BREAK,
    LAND_PATTERN, LAND_FINAL, FLARE, GO_AROUND, EMERGENCY_GLIDE
}
