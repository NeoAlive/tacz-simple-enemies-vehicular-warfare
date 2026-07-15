package com.neoalive.tacz_sewv.mixin.client;

import com.atsuishio.superbwarfare.data.vehicle.subdata.OBBInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.event.client.ClientGlowRenderHandler;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draw SEM's selection particles around the whole vehicle footprint, not a unit-sized circle. */
@Mixin(ClientGlowRenderHandler.class)
public abstract class MixinClientGlowRenderHandler {

    private static final DustParticleOptions tacz_sewv$SELECTION_PARTICLE =
            new DustParticleOptions(new Vector3f(0.0F, 1.0F, 0.3F), 1.0F);
    private static final double tacz_sewv$OUTLINE_MARGIN = 0.35;
    private static final double tacz_sewv$PARTICLE_SPACING = 0.75;

    @Inject(method = "spawnCircle", at = @At("HEAD"), cancellable = true, remap = false)
    private static void tacz_sewv$spawnVehicleOutline(Level level, Entity entity, CallbackInfo ci) {
        if (!(entity instanceof VehicleEntity vehicle)) return;

        double halfWidth = Math.max(1.0, vehicle.getBbWidth() / 2.0);
        double halfLength = halfWidth;
        for (OBBInfo obb : vehicle.getObb()) {
            Vec3 size = obb.getSize();
            Vec3 position = obb.getPosition();
            halfWidth = Math.max(halfWidth, Math.abs(position.x) + Math.abs(size.x));
            halfLength = Math.max(halfLength, Math.abs(position.z) + Math.abs(size.z));
        }

        halfWidth += tacz_sewv$OUTLINE_MARGIN;
        halfLength += tacz_sewv$OUTLINE_MARGIN;
        double yaw = Math.toRadians(vehicle.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        int widthSteps = Math.max(2, (int) Math.ceil(halfWidth * 2.0 / tacz_sewv$PARTICLE_SPACING));
        int lengthSteps = Math.max(2, (int) Math.ceil(halfLength * 2.0 / tacz_sewv$PARTICLE_SPACING));

        // A rectangle follows the vehicle's OBB footprint much better than SEM's
        // fixed 0.6-block circle, including for tanks roughly 5 by 10 blocks.
        for (int i = 0; i <= widthSteps; i++) {
            double x = -halfWidth + (halfWidth * 2.0 * i / widthSteps);
            tacz_sewv$spawnParticle(level, vehicle, x, -halfLength, sin, cos);
            tacz_sewv$spawnParticle(level, vehicle, x, halfLength, sin, cos);
        }
        for (int i = 1; i < lengthSteps; i++) {
            double z = -halfLength + (halfLength * 2.0 * i / lengthSteps);
            tacz_sewv$spawnParticle(level, vehicle, -halfWidth, z, sin, cos);
            tacz_sewv$spawnParticle(level, vehicle, halfWidth, z, sin, cos);
        }

        ci.cancel();
    }

    private static void tacz_sewv$spawnParticle(Level level, VehicleEntity vehicle,
                                                  double localX, double localZ, double sin, double cos) {
        double x = vehicle.getX() + localX * cos - localZ * sin;
        double z = vehicle.getZ() + localX * sin + localZ * cos;
        level.addParticle(tacz_sewv$SELECTION_PARTICLE, x, vehicle.getY() + 0.05, z, 0.0, 0.0, 0.0);
    }
}
