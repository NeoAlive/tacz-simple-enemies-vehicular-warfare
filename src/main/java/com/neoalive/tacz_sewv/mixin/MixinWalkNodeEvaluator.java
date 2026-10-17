package com.neoalive.tacz_sewv.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.entity.ai.navigation.GroundVehicleNodeEvaluator;
import com.neoalive.tacz_sewv.entity.ai.navigation.VehiclePathObstacles;

/**
 * Treats SuperbWarfare (and addon) vehicle hitboxes as blocked for SEM infantry pathfinding.
 *
 * <p>Vanilla only classifies blocks; SBW OBB hulls are invisible to that path. See
 * {@link VehiclePathObstacles}. Mounted crews exclude their own hull so a driver that
 * somehow hit this path does not self-block; on-foot approaches stop at the perimeter
 * (BoardVehicleGoal mounts from there).
 *
 * <p>{@link GroundVehicleNodeEvaluator} overrides this method and never reaches here —
 * vehicle AI keeps its soft peer spacing instead of hard-blocking peer hulls. The
 * instanceof guard is belt-and-suspenders if a future change calls {@code super}.
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class MixinWalkNodeEvaluator extends NodeEvaluator {

    @Inject(
            method = "getBlockPathType(Lnet/minecraft/world/level/BlockGetter;IIILnet/minecraft/world/entity/Mob;)Lnet/minecraft/world/level/pathfinder/BlockPathTypes;",
            at = @At("RETURN"),
            cancellable = true
    )
    private void tacz_sewv$blockVehicleCells(BlockGetter level, int x, int y, int z, Mob mob,
                                             CallbackInfoReturnable<BlockPathTypes> cir) {
        if ((Object) this instanceof GroundVehicleNodeEvaluator) return;

        BlockPathTypes current = cir.getReturnValue();
        if (current != null && current.getMalus() < 0.0F) return; // already impassable
        if (!(mob instanceof AbstractUnit unit)) return;
        // Mounted crews path with GroundVehicleNodeEvaluator; if they ever hit vanilla
        // WalkNodeEvaluator, still exclude their own hull so they do not self-block.
        if (unit.isPassenger()) return;
        if (!(unit.level() instanceof ServerLevel serverLevel)) return;

        // Check the whole footprint — a 1-wide mob is a single column, but wider
        // footprints must not accept a node whose corner alone is clear of the hull.
        int w = Math.max(1, this.entityWidth);
        int h = Math.max(1, this.entityHeight);
        int d = Math.max(1, this.entityDepth);
        for (int ix = 0; ix < w; ix++) {
            for (int iy = 0; iy < h; iy++) {
                for (int iz = 0; iz < d; iz++) {
                    if (VehiclePathObstacles.blocks(serverLevel, x + ix, y + iy, z + iz, -1)) {
                        cir.setReturnValue(BlockPathTypes.BLOCKED);
                        return;
                    }
                }
            }
        }
    }
}
