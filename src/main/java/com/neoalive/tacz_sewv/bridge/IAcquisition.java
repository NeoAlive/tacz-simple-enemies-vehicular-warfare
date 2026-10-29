package com.neoalive.tacz_sewv.bridge;

/**
 * Per-unit target-acquisition state for {@link com.neoalive.tacz_sewv.entity.ai.sensor.UnitAcquisition},
 * mixed onto SEM's {@code AbstractUnit}. Transient: it names a target by network id, and a unit
 * re-noticing its surroundings after a reload is harmless.
 */
public interface IAcquisition {

    int sewv$getAcqTargetId();

    void sewv$setAcqTargetId(int id);

    long sewv$getAcqReadyAt();

    void sewv$setAcqReadyAt(long gameTime);

    long sewv$getAcqLastSeen();

    void sewv$setAcqLastSeen(long gameTime);
}
