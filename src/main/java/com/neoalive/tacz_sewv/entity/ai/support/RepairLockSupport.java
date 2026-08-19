package com.neoalive.tacz_sewv.entity.ai.support;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.nbt.CompoundTag;

/**
 * Per-hull "an engineer is actively working on this" lock, read by {@link
 * com.neoalive.tacz_sewv.entity.ai.core.VehicleDriver} to hold the hull still while under repair.
 *
 * <p>Stored as a rolling absolute-game-time deadline rather than a bare marker — the same shape
 * {@link com.neoalive.tacz_sewv.bridge.IMortarCrew}'s fire-mission expiry uses — so the lock is
 * self-healing: {@link com.neoalive.tacz_sewv.entity.ai.goal.RepairGoal} refreshes it every tick
 * it is actually in range and working, and if the engineer dies, despawns, or the chunk unloads
 * before its own {@code stop()} can clear it, the lock simply expires a few seconds later with no
 * cleanup hook required.
 */
public final class RepairLockSupport {

    private static final String TAG_LOCK_UNTIL = "sewv:repair_lock_until";
    /** How long the lock survives with no refresh — covers one missed tick cleanly, no more. */
    private static final long GRACE_TICKS = 60;

    private RepairLockSupport() {}

    /** Call every tick the engineer is actually in range and working. */
    public static void refresh(VehicleEntity hull) {
        hull.getPersistentData().putLong(TAG_LOCK_UNTIL, hull.level().getGameTime() + GRACE_TICKS);
    }

    /** Call on a clean stop (target lost, engineer walked off, repair finished) for an instant release. */
    public static void clear(VehicleEntity hull) {
        hull.getPersistentData().remove(TAG_LOCK_UNTIL);
    }

    public static boolean isLocked(VehicleEntity hull) {
        CompoundTag data = hull.getPersistentData();
        if (!data.contains(TAG_LOCK_UNTIL)) return false;
        return hull.level().getGameTime() < data.getLong(TAG_LOCK_UNTIL);
    }
}
