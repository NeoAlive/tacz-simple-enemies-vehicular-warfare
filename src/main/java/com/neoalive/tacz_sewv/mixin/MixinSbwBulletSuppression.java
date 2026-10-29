package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.entity.ai.suppression.SuppressionBus;

/**
 * An SBW bullet hitting a block suppresses SEM infantry near the impact — the SBW counterpart of the
 * TaCZ {@code AmmoHitBlockEvent} SEM already listens to. Every vehicle machine gun fires these, AI or
 * player, and SEM has no idea they exist. Rounds that explode are left to {@code ExplosionEvent}.
 *
 * <p>{@code onHitBlock} is SBW's override of the vanilla method, so it is remapped like any vanilla
 * target. {@code require = 0}: losing this costs suppression from SBW bullets, not the game.
 */
@Mixin(ProjectileEntity.class)
public abstract class MixinSbwBulletSuppression {

    @Inject(method = "onHitBlock", at = @At("HEAD"), require = 0)
    private void tacz_sewv$suppressAtImpact(BlockHitResult hit, CallbackInfo ci) {
        ProjectileEntity self = (ProjectileEntity) (Object) this;
        if (self.level().isClientSide) return;
        SuppressionBus.onSbwBulletImpact(self, hit, self.getExplosionRadius());
    }
}
