package com.neoalive.tacz_sewv.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.nekoyuni.SimpleEnemyMod.entity.ai.sensing.TacticalLineOfSightHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.entity.ai.sensor.SemLosCache;
import com.neoalive.tacz_sewv.util.LoadedClip;

/**
 * SEM's infantry line of sight ({@code hasFoliageTransparentLineOfSight}, asked by five goals) casts with
 * {@code Level.clip} and walks blocked rays through {@code Level.getBlockState}, both of which load a
 * missing chunk on the server. Read loaded chunks only, as every other AI ray here does. The answer is
 * also reused per observer and target ({@link SemLosCache}), since the five goals mostly ask the same thing.
 */
@Mixin(value = TacticalLineOfSightHelper.class, remap = false)
public abstract class MixinSemLineOfSight {

    @Redirect(method = "hasColliderLineOfSight", require = 0,
            at = @At(value = "INVOKE", remap = true,
                    target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private static BlockHitResult tacz_sewv$loadedClip(Level level, ClipContext context) {
        return LoadedClip.clip(level, context);
    }

    @Redirect(method = "hasSoftObstacleTransparentLineOfSight", require = 0,
            at = @At(value = "INVOKE", remap = true,
                    target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState tacz_sewv$loadedBlock(Level level, BlockPos pos) {
        return LoadedClip.blockAt(level, pos);
    }

    @Inject(method = "hasFoliageTransparentLineOfSight", at = @At("HEAD"), cancellable = true, require = 0)
    private static void tacz_sewv$reuseSight(LivingEntity observer, LivingEntity target,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (observer == null || target == null) return;
        int cached = SemLosCache.lookup(observer, target);
        if (cached >= 0) cir.setReturnValue(cached == 1);
    }

    @Inject(method = "hasFoliageTransparentLineOfSight", at = @At("RETURN"), require = 0)
    private static void tacz_sewv$keepSight(LivingEntity observer, LivingEntity target,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (observer == null || target == null) return;
        SemLosCache.store(observer, target, cir.getReturnValueZ());
    }
}
