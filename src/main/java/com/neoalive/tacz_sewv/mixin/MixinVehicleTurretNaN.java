package com.neoalive.tacz_sewv.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rejects non-finite turret/weapon-station rotations on SBW's {@code VehicleEntity}.
 *
 * <p>SBW aims an AI-crewed turret through a path players never touch. Its tick picks
 * one of two branches on the turret controller:
 * {@code Player -> adjustTurretAngle()} (pure geometry — bbox centre + look·512, no
 * ballistics) versus {@code Mob -> turretAutoAimFromUuid()}, which runs
 * {@code RangeTool.calculateFiringSolution}. Only this mod ever puts a Mob in that
 * seat, so only we reach the solver.
 *
 * <p>That solver Newton-solves flight time from {@code t0 = |d0| / muzzleVelocity},
 * then either returns a solution for {@code t > 0} or falls into a branch that
 * DIVIDES BY {@code t}. It is numerically sound in general (fuzzed over every real
 * weapon profile), but returns NaN on three exact inputs: {@code muzzleVelocity == 0}
 * (t=Inf -> 0*Inf=NaN -> t=NaN -> fails {@code t > 0} -> 0/0 in the fallback),
 * {@code |d0| == 0}, or NaN/Inf already in the launch/target/velocity vectors.
 * A zero muzzle velocity is reachable in practice: {@code GunProp.VELOCITY} DEFAULTS
 * TO 0, so placeholder weapons ({@code "Empty"}/{@code "Nothing"}, common in addon
 * packs) resolve to 0, and {@code AddShooterDeltaMovement} weapons compute
 * {@code deltaMovement.length() * VELOCITY} — zero whenever the hull is parked, which
 * is exactly what the standoff-ring doctrine makes it do.
 *
 * <p>Without this guard a single NaN is PERMANENT: {@code turretYRot}/{@code gunYRot}
 * are plain unguarded fields that accumulate off their own previous value, and
 * {@code Mth.clamp(NaN, min, max)} returns NaN ({@code NaN < min} is false, then
 * {@code Math.min(NaN, max)} is NaN). So {@code clamp(NaN + delta)} stays NaN forever:
 * the turret never rotates again, while firing continues (the fire path never checks
 * angle finiteness) and every subsequent tick pushes the NaN barrel vector onto the
 * crew via SBW's {@code clampRotation}, spamming vanilla's
 * "Invalid entity rotation: NaN, discarding" from {@code Entity.setYRot}.
 *
 * <p>Dropping the write leaves the last good angle in place, so the turret holds its
 * bearing for the bad ticks and resumes the moment a finite solution returns — and a
 * turret that already latched NaN in a saved world recovers instead of staying bricked.
 * {@link com.neoalive.tacz_sewv.entity.ai.VehicleWeapons} separately refuses to select
 * zero-velocity slots, which removes the common cause; this is the backstop for the
 * dynamic cases it can't see (parked {@code AddShooterDeltaMovement} hulls, addon guns
 * whose velocity resolves to 0 only at runtime, poisoned target state).
 *
 * <p>All four setters accumulate off their own previous value, so all four can latch.
 * SBW reaches them via {@code invokevirtual} (verified in bytecode) even from inside
 * {@code VehicleEntity} itself, so injecting on the setters catches every writer.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleTurretNaN {

    @Inject(method = "setTurretYRot", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$rejectNaNTurretYRot(float value, CallbackInfo ci) {
        if (!Float.isFinite(value)) ci.cancel();
    }

    @Inject(method = "setTurretXRot", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$rejectNaNTurretXRot(float value, CallbackInfo ci) {
        if (!Float.isFinite(value)) ci.cancel();
    }

    @Inject(method = "setGunYRot", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$rejectNaNGunYRot(float value, CallbackInfo ci) {
        if (!Float.isFinite(value)) ci.cancel();
    }

    @Inject(method = "setGunXRot", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$rejectNaNGunXRot(float value, CallbackInfo ci) {
        if (!Float.isFinite(value)) ci.cancel();
    }
}
