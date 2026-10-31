package com.neoalive.tacz_sewv.mixin.tripod;

import java.lang.reflect.Field;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.compat.TripodCompat;

/**
 * The heat ray burns the HULL it crosses ({@link TripodCompat#heatRayHullHits}). The mod's own
 * {@code trace} only scans {@code LivingEntity}, and a {@code VehicleEntity} is not one, so a hull
 * was only ever hurt second-hand through SBW forwarding its enclosed crew's damage: an empty hull was
 * immune and a full one took the beam once per rider. Hooked at {@code trace} RETURN, the one place
 * that has the beam's exact segment for this tick, once per beam.
 *
 * <p>{@code owner} is read reflectively: its type ({@code TripodEntity}) is not on the compile
 * classpath, so a {@code @Shadow} cannot name it.
 */
@Pseudo
@Mixin(targets = "com.xiaoxiang.tripod.entity.locomotion.TripodHeatRay", remap = false)
public abstract class MixinTripodHeatRay {

    @Unique
    private static Field tacz_sewv$owner;
    @Unique
    private static boolean tacz_sewv$ownerResolved;

    @Inject(method = "trace", at = @At("RETURN"), remap = false)
    private void tacz_sewv$burnHulls(Vec3 start, Vec3 dir, int beam, CallbackInfoReturnable<Vec3> cir) {
        if (!tacz_sewv$ownerResolved) {
            tacz_sewv$ownerResolved = true;
            try {
                tacz_sewv$owner = getClass().getDeclaredField("owner");
                tacz_sewv$owner.setAccessible(true);
            } catch (Throwable renamed) {
                tacz_sewv$owner = null;
            }
        }
        if (tacz_sewv$owner == null || cir.getReturnValue() == null) return;
        try {
            if (tacz_sewv$owner.get(this) instanceof Entity tripod) {
                TripodCompat.heatRayHullHits(tripod, start, cir.getReturnValue(), beam);
            }
        } catch (IllegalAccessException unreachable) {
            // setAccessible succeeded above.
        }
    }
}
