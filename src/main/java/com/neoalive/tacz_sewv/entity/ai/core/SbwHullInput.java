package com.neoalive.tacz_sewv.entity.ai.core;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;

/** {@link HullInput} over an SBW hull: exactly the four setters the drive code always called. */
public final class SbwHullInput implements HullInput {

    private final VehicleEntity vehicle;

    public SbwHullInput(VehicleEntity vehicle) {
        this.vehicle = vehicle;
    }

    public VehicleEntity vehicle() {
        return this.vehicle;
    }

    @Override
    public Entity hull() {
        return this.vehicle;
    }

    @Override
    public void setInputs(boolean forward, boolean back, boolean left, boolean right) {
        this.vehicle.setForwardInputDown(forward);
        this.vehicle.setBackInputDown(back);
        this.vehicle.setLeftInputDown(left);
        this.vehicle.setRightInputDown(right);
    }

    @Override
    public Vector3f forwardDirection() {
        return this.vehicle.getForwardDirection();
    }
}
