package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import org.joml.Matrix4d;
import org.joml.Vector4d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps a non-finite seat transform from being written into a passenger's POSITION.
 *
 * <p>Rotations are self-healing; positions are not. Vanilla {@code Entity.setYRot}
 * rejects non-finite input, so a NaN rotation is merely logged and dropped. But
 * {@code Entity.setPos} has NO such guard — a NaN position is stored, saved to NBT,
 * and then {@code Entity.load} throws {@code IllegalStateException: "Entity has
 * invalid position"} on the way back in. When the passenger is the player that is
 * fatal: "Couldn't place player in world" and the save will not open again.
 *
 * <p>SBW's {@code passengerPos} places every rider by pushing the seat offset through
 * the hull transform and calling {@code setPos} with the result, unchecked. The Y term
 * of that transform is built (VehicleVecUtils.getVehicleYOffsetTransform) as
 * {@code Mth.lerp(t, yOld + rotateOffsetHeight, getY() + rotateOffsetHeight)}, and
 * {@code lerp} is {@code start + t*(end - start)} — so a non-finite {@code yOld}
 * against a finite {@code getY()} yields {@code Inf + (-Inf) = NaN} in Y ALONE, while
 * X and Z stay clean because only Y is built that way. That asymmetry is the
 * fingerprint: no NaN rotation can corrupt exactly one axis (yaw hits X/Z, pitch Y/Z,
 * roll X/Y), so this is a separate fault from the turret-aim NaN that
 * {@link MixinVehicleTurretNaN} guards.
 *
 * <p>Substituting the hull's own position keeps the rider attached and finite — one
 * tick at the hull centre instead of a permanently unloadable world. The next tick
 * re-derives the seat normally once the transform is sane again ({@code yOld} is
 * transient and never persisted, so it recovers on its own).
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehiclePassengerNaN {

    @Redirect(
            method = "passengerPos",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;"
                            + "transformPosition(Lorg/joml/Matrix4d;DDD)Lorg/joml/Vector4d;"),
            remap = false)
    private Vector4d tacz_sewv$sanitizeSeatPos(
            VehicleEntity self, Matrix4d transform, double x, double y, double z) {
        Vector4d pos = self.transformPosition(transform, x, y, z);
        if (Double.isFinite(pos.x) && Double.isFinite(pos.y) && Double.isFinite(pos.z)) {
            return pos;
        }
        return new Vector4d(self.getX(), self.getY(), self.getZ(), 1.0);
    }
}
