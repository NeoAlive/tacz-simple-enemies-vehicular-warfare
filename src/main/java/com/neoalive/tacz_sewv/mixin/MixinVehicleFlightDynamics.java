package com.neoalive.tacz_sewv.mixin;

import java.util.List;

import com.atsuishio.superbwarfare.data.vehicle.DefaultVehicleData;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.heli.HeliRuntime;
import com.neoalive.tacz_sewv.heli.HeliTrace;
import com.neoalive.tacz_sewv.heli.IFlightDynamics;

/**
 * Gives every SBW hull the {@link IFlightDynamics} slots, and keeps SBW's landing-gear alignment
 * ({@code terrainCompact}, which rewrites pitch and roll within 4 m of the ground) off a hull our
 * integrator is flying. An empty TerrainCompat list makes {@code baseTick} skip the call
 * (javap-verified: {@code if (!list.isEmpty() ...) terrainCompact(list)}). A parked hull keeps it,
 * so it still settles onto its skids.
 *
 * <p>Nothing else redirects this call (checked over {@code src/main} and every non-SBW jar in
 * {@code libs/}). {@code baseTick} is vanilla's override (SRG in production), so the method name
 * keeps remap ON; the SBW-owned call target is {@code remap = false}.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleFlightDynamics implements IFlightDynamics {

    @Unique
    private HeliRuntime tacz_sewv$heliRuntime;
    @Unique
    private int tacz_sewv$flightMode;
    @Unique
    private float tacz_sewv$clientRoll = Float.NaN;

    @Override
    public HeliRuntime sewv$heliRuntime() {
        return this.tacz_sewv$heliRuntime;
    }

    @Override
    public void sewv$setHeliRuntime(HeliRuntime runtime) {
        this.tacz_sewv$heliRuntime = runtime;
    }

    @Override
    public int sewv$flightMode() {
        return this.tacz_sewv$flightMode;
    }

    @Override
    public void sewv$setFlightMode(int mode) {
        this.tacz_sewv$flightMode = mode;
    }

    @Override
    public float sewv$clientRoll() {
        return this.tacz_sewv$clientRoll;
    }

    @Override
    public void sewv$setClientRoll(float roll) {
        this.tacz_sewv$clientRoll = roll;
    }

    @Redirect(
            method = "baseTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/atsuishio/superbwarfare/data/vehicle/DefaultVehicleData;getTerrainCompat()Ljava/util/List;",
                    remap = false))
    private List<?> tacz_sewv$noTerrainCompatInFlight(DefaultVehicleData data) {
        HeliTrace.noteTerrainCompat((VehicleEntity) (Object) this);
        return this.tacz_sewv$flightMode == IFlightDynamics.FLYING ? List.of() : data.getTerrainCompat();
    }

    /** Trace probe: the hull right after {@code baseTick}'s one {@code move()} (javap: @2675). */
    @Inject(
            method = "baseTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
                    shift = At.Shift.AFTER))
    private void tacz_sewv$traceAfterMove(CallbackInfo ci) {
        HeliTrace.postMove((VehicleEntity) (Object) this);
    }
}
