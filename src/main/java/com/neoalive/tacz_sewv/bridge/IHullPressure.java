package com.neoalive.tacz_sewv.bridge;

/**
 * Per-hull "under fire" state for {@link com.neoalive.tacz_sewv.entity.ai.suppression.HullPressure},
 * mixed onto SBW's {@code VehicleEntity} by {@code MixinVehicleHullPressure}. Transient: incoming fire
 * from before a reload is over.
 *
 * <p>Stored as the value at its last write plus that write's game time; readers decay it lazily, so
 * nothing ticks it.
 */
public interface IHullPressure {

    float sewv$getPressureRaw();

    void sewv$setPressureRaw(float value);

    long sewv$getPressureStamp();

    void sewv$setPressureStamp(long gameTime);

    /**
     * Network id of the last living attacker whose hit counted as heavy, or -1; and when. Transient, like every
     * network-id order in {@code bridge/}: an id means nothing across a reload.
     */
    int sewv$getHeavyAttackerId();

    long sewv$getHeavyAttackStamp();

    void sewv$setHeavyAttacker(int entityId, long gameTime);
}
