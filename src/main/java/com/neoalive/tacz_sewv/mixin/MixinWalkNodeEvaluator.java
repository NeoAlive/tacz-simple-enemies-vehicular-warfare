package com.neoalive.tacz_sewv.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.entity.ai.navigation.VehiclePathObstacles;

/**
 * Treats SuperbWarfare (and addon) vehicle hitboxes as blocked for SEM infantry pathfinding.
 *
 * <p>Vanilla only classifies blocks; SBW OBB hulls are invisible to that path. See
 * {@link VehiclePathObstacles}. Mounted crews exclude their own hull so driver pathfinding
 * does not self-block; on-foot approaches stop at the perimeter (BoardVehicleGoal mounts
 * from there).
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class MixinWalkNodeEvaluator {

    @Inject(
            method = "getBlockPathType(Lnet/minecraft/world/level/BlockGetter;IIILnet/minecraft/world/entity/Mob;)Lnet/minecraft/world/level/pathfinder/BlockPathTypes;",
            at = @At("RETURN"),
            cancellable = true
    )
    private void tacz_sewv$blockVehicleCells(BlockGetter level, int x, int y, int z, Mob mob,
                                             CallbackInfoReturnable<BlockPathTypes> cir) {
        BlockPathTypes current = cir.getReturnValue();
        if (current != null && current.getMalus() < 0.0F) return; // already impassable
        if (!(mob instanceof AbstractUnit unit)) return;
        if (!(unit.level() instanceof ServerLevel serverLevel)) return;

        Entity ride = unit.getVehicle();
        int exclude = ride != null ? ride.getId() : -1;
        if (VehiclePathObstacles.blocks(serverLevel, x, y, z, exclude)) {
            cir.setReturnValue(BlockPathTypes.BLOCKED);
        }
    }
}
