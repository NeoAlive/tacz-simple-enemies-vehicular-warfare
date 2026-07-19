package com.neoalive.tacz_sewv.bridge;

/**
 * A unit's pending escort target — the entity it should stick beside, worked by {@code EscortGoal}.
 *
 * <p>Transient, by the same rule as {@link IVehicleBoarder}: it names an entity by <b>network id</b>,
 * which is not stable across sessions, so it must not persist — a reload simply drops the order.
 * Backed by a {@code @Unique} field in {@code MixinPmcUnitEntity}; {@code -1} means "not escorting".
 */
public interface IEscort {

    void tacz_sewv$setEscortTargetId(int id);

    int tacz_sewv$getEscortTargetId();

    /** Convenience: whether this unit currently has an escort order. */
    default boolean tacz_sewv$isEscorting() {
        return tacz_sewv$getEscortTargetId() != -1;
    }
}
