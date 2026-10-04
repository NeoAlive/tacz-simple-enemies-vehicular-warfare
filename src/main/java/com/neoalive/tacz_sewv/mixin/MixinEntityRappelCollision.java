package com.neoalive.tacz_sewv.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.entity.ai.support.RappelSupport;

/**
 * A rider on a rappel rope does not collide with the hull it hangs from (AABB hulls; the OBB half
 * is in {@link MixinVehicleCrushAllies}). The rope top lies inside a wide hull's box, and vanilla
 * collision from inside it blocks the rider's own downward moves.
 */
@Mixin(Entity.class)
public abstract class MixinEntityRappelCollision {

    @Inject(method = "canCollideWith", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$ropeIgnoresHull(Entity other, CallbackInfoReturnable<Boolean> cir) {
        if (RappelSupport.onRopeOf((Entity) (Object) this, other)) cir.setReturnValue(false);
    }
}
