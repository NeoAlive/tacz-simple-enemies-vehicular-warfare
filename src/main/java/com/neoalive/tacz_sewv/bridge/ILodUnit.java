package com.neoalive.tacz_sewv.bridge;

/**
 * Mixed onto SEM's {@code AbstractUnit} (by {@code MixinAbstractUnit}) to carry the unit's AI
 * level-of-detail state for {@link com.neoalive.tacz_sewv.entity.ai.support.UnitLod}.
 *
 * <p>Transient by design: the tier is recomputed from player positions every index rebuild, and
 * a reloaded unit starting at FULL (all fields zero) is the safe default.
 */
public interface ILodUnit {

    byte sewv$lodTier();

    /** Game time the tier was assigned; a stale tier reads as FULL. */
    long sewv$lodStamp();

    void sewv$setLod(byte tier, long gameTime);

    /** Game time until which the unit runs at full rate regardless of tier. */
    long sewv$lodWakeUntil();

    void sewv$setLodWakeUntil(long gameTime);

    /** Last target that woke the unit, so a re-asserted same target does not keep it awake. */
    int sewv$lodWakeTargetId();

    void sewv$setLodWakeTargetId(int id);
}
