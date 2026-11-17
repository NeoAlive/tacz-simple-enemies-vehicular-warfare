package com.neoalive.tacz_sewv.mixin;

import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.neoalive.tacz_sewv.bridge.IAcquisition;

/** Carries {@link IAcquisition} on every SEM unit. State only; the gate is {@code MixinSemTargetAcquisition}. */
@Mixin(AbstractUnit.class)
public abstract class MixinUnitAcquisitionState implements IAcquisition {

    @Unique
    private int tacz_sewv$acqTargetId = -1;

    @Unique
    private long tacz_sewv$acqReadyAt = Long.MIN_VALUE;

    @Unique
    private long tacz_sewv$acqLastSeen = Long.MIN_VALUE;

    @Override
    public int sewv$getAcqTargetId() {
        return this.tacz_sewv$acqTargetId;
    }

    @Override
    public void sewv$setAcqTargetId(int id) {
        this.tacz_sewv$acqTargetId = id;
    }

    @Override
    public long sewv$getAcqReadyAt() {
        return this.tacz_sewv$acqReadyAt;
    }

    @Override
    public void sewv$setAcqReadyAt(long gameTime) {
        this.tacz_sewv$acqReadyAt = gameTime;
    }

    @Override
    public long sewv$getAcqLastSeen() {
        return this.tacz_sewv$acqLastSeen;
    }

    @Override
    public void sewv$setAcqLastSeen(long gameTime) {
        this.tacz_sewv$acqLastSeen = gameTime;
    }
}
