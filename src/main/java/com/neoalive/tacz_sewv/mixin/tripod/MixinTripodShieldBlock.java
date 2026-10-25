package com.neoalive.tacz_sewv.mixin.tripod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.compat.TripodCompat;

/**
 * Tripod Invasion's shield as a one-way wall for the AI: a crew caged inside a shell (the tripod
 * walked onto it, or the shield rebuilt around it) drives out on its standoff retreat instead of
 * pushing against an invisible wall forever. The rule is {@link TripodCompat#passesOutward}.
 *
 * <p>Targeted by name ({@code @Pseudo}: the class is not on the compile classpath), so nothing
 * links against the mod; its own optional config and {@code TripodMixinPlugin} keep it unqueued
 * without the mod. The method is listed under both its
 * dev and SRG names (only one exists in a given environment), the same trick the tripod mod uses for
 * its own {@code hasLineOfSight} hook. {@code getShape} delegates to this method, so an AI unit's own
 * outline rays see out through the wall too; projectiles carry their own context and still hit it.
 * Should the mod rename the method, the miss is logged at load and the cage comes back.
 */
@Pseudo
@Mixin(targets = "com.xiaoxiang.tripod.shield.TripodShieldBlock", remap = false)
public abstract class MixinTripodShieldBlock {

    @Inject(method = {"getCollisionShape", "m_5939_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$oneWayForAi(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context,
                                       CallbackInfoReturnable<VoxelShape> cir) {
        if (context instanceof EntityCollisionContext ec && TripodCompat.passesOutward(ec.getEntity(), pos)) {
            cir.setReturnValue(Shapes.empty());
        }
    }
}
