package com.neoalive.tacz_sewv.entity.ai.support;

import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.bridge.ICaptureOrder;
import com.neoalive.tacz_sewv.bridge.IEntrenched;
import com.neoalive.tacz_sewv.bridge.IEscort;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.bridge.ITowRecovery;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;

/**
 * Is an on-foot unit's walking its own to give? The one answer the priority-1 movement goals share
 * ({@code InfantrySquadGoal}, {@code MarchGoal}) and the march's recruiting asks before taking someone.
 *
 * <p>At equal goal priority whoever holds MOVE keeps it, so the only way for these goals to let a board order, a
 * mortar claim, a capture, an entrenchment, a tow recovery or an escort in is to decline while one stands — and
 * a recruiter must not take a unit that one of those already has.
 */
public final class InfantryOrders {

    private InfantryOrders() {}

    /** On foot, not boarding, crewing, capturing, dug in, towing or escorting. Ignores targets: callers decide. */
    public static boolean unordered(AbstractUnit unit) {
        if (unit.isPassenger()) return false;
        if (unit instanceof IVehicleBoarder b && (b.tacz_sewv$getMountTargetId() != -1 || b.tacz_sewv$isBoarding())) {
            return false;
        }
        if (unit instanceof IMortarCrew m && m.sewv$getMortarTargetId() != -1) return false;
        if (unit instanceof ICaptureOrder c && c.sewv$hasCaptureOrder()) return false;
        if (unit instanceof IEntrenched e && e.sewv$isEntrenched()) return false;
        if (unit instanceof ITowRecovery t && t.tacz_sewv$isTowingRecovery()) return false;
        return !(unit instanceof IEscort esc && esc.tacz_sewv$isEscorting());
    }

    /** {@link #unordered} and out of contact. */
    public static boolean free(AbstractUnit unit) {
        return unit.getTarget() == null && unordered(unit);
    }
}
