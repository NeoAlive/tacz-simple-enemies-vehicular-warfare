package com.neoalive.tacz_sewv.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.entity.ai.navigation.VehiclePathObstacles;

/**
 * SEM / vanilla {@code hasLineOfSight} is a block clip. SBW hulls are entities, so
 * infantry treat a tank in the way as empty air, hold, and dump rounds into it.
 * After vanilla says the line is clear, overlay the vehicle occupancy cache —
 * the same cells on-foot units already path around. Mounted crews and the
 * target's own hull are excluded so a gunner can still see out and a rider
 * remains shootable.
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntityLos {

    @Inject(method = "hasLineOfSight", at = @At("RETURN"), cancellable = true)
    private void tacz_sewv$hullsBlockLos(Entity target, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) return;
        if (!((Object) this instanceof AbstractUnit)) return;
        if (!(target instanceof LivingEntity living)) return;
        if (VehiclePathObstacles.occludesLos((Entity) (Object) this, living)) {
            cir.setReturnValue(false);
        }
    }
}
