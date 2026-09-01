package com.neoalive.tacz_sewv.bridge;

/**
 * A tower driver's pending tow-recovery victim — the hull to link and pull free, worked by
 * {@link com.neoalive.tacz_sewv.entity.ai.support.TowRecoverySupport}.
 *
 * <p>Transient, by the same rule as {@link IEscort}: it names an entity by <b>network id</b>,
 * which is not stable across sessions. {@code -1} means not towing.
 */
public interface ITowRecovery {

    void tacz_sewv$setTowVictimId(int id);

    int tacz_sewv$getTowVictimId();

    /** Ticks the assigned victim has been missing or invalid — chunk unload grace, not an instant drop. */
    int tacz_sewv$getTowVictimGraceTicks();

    void tacz_sewv$setTowVictimGraceTicks(int ticks);

    default boolean tacz_sewv$isTowingRecovery() {
        return tacz_sewv$getTowVictimId() != -1;
    }
}
