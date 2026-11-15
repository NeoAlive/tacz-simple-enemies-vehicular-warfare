package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.projectile.DestroyableProjectile;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.entity.ai.core.ProjectileOwners;

/**
 * The blast half of the sibling-bomb filter in {@link MixinLevelObbHitIndex}: a bomb from an SEM-crewed hull takes no
 * damage from anything that came off the same hull, so one detonation does not set off the rest
 * of its stick in the air.
 */
@Mixin(DestroyableProjectile.class)
public abstract class MixinDestroyableProjectileSiblingBlast {

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true, require = 0)
    private void tacz_sewv$ignoreSiblingBlast(DamageSource source, float amount,
                                              CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (ProjectileOwners.crewedHull(self) == null) return;
        if (ProjectileOwners.sameCrewedHull(self, source.getDirectEntity())
                || ProjectileOwners.sameCrewedHull(self, source.getEntity())) {
            cir.setReturnValue(false);
        }
    }
}
