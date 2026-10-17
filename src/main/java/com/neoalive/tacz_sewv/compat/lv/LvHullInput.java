package com.neoalive.tacz_sewv.compat.lv;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.control.ControlUnit;

import com.neoalive.tacz_sewv.entity.ai.core.HullInput;

/**
 * {@link HullInput} over an LV hull: writes its {@link ControlUnit} through {@link LvSteering}.
 *
 * <p>Those fields are plain server-side state that LV's own {@code tickMove} reads every tick, and
 * LV only resets them when seat 0 is empty — so while our crew holds the wheel they persist, and
 * every goal that drives through this must release them when it stops.
 */
final class LvHullInput implements HullInput {

    private final AbstractVehicle hull;
    private final LvSteering.Kind kind;

    LvHullInput(AbstractVehicle hull, LvSteering.Kind kind) {
        this.hull = hull;
        this.kind = kind;
    }

    @Override
    public Entity hull() {
        return this.hull;
    }

    @Override
    public void setInputs(boolean forward, boolean back, boolean left, boolean right) {
        LvSteering.Keys k = LvSteering.translate(this.kind, forward, back, left, right, forwardSpeed());
        ControlUnit c = this.hull.controlUnit;
        c.forward = k.forward();
        c.backward = k.backward();
        c.left = k.left();
        c.right = k.right();
        c.up = k.handbrake();
    }

    @Override
    public Vector3f forwardDirection() {
        Vec3 d = VectorUtil.rotToVec(0, this.hull.getYRot());
        return new Vector3f((float) d.x, 0.0F, (float) d.z);
    }

    /** Velocity along the bow, the quantity LV's own track engine tests. */
    private double forwardSpeed() {
        return this.hull.getDeltaMovement().dot(VectorUtil.rotToVec(this.hull.getXRot(), this.hull.getYRot()));
    }

    static LvSteering.Kind steeringKind(LvHulls.Kind kind) {
        return switch (kind) {
            case WHEELED -> LvSteering.Kind.WHEELED;
            case TRACKED -> LvSteering.Kind.TRACKED;
            case VESSEL -> LvSteering.Kind.VESSEL;
            case ROTARY -> throw new IllegalArgumentException("rotorcraft are flown by LvRotorGoal, not steered by keys");
        };
    }
}
