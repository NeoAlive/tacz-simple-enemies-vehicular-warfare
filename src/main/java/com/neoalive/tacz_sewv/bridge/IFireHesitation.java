package com.neoalive.tacz_sewv.bridge;

/**
 * Per-shooter (seat) state for {@link com.neoalive.tacz_sewv.entity.ai.support.FireHesitation}.
 * Transient: a target network id is not stable across sessions, and a reload re-hesitating once
 * is harmless.
 */
public interface IFireHesitation {

    int sewv$getHesTargetId();

    void sewv$setHesTargetId(int id);

    long sewv$getHesReadyAt();

    void sewv$setHesReadyAt(long gameTime);
}
