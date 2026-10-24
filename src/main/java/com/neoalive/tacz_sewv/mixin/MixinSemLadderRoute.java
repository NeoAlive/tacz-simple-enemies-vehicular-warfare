package com.neoalive.tacz_sewv.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SEM's ladder fallback reads blocks with {@code Level.getBlockState} across the box between the
 * unit and its destination (+24 margin), and every SEM move goes through it first. A destination
 * in an unloaded chunk — a plane pilot under a map-click MOVE_TO_POSITION, or any far order —
 * sync-loads that chunk on the server thread, every call: multi-second spikes, then the watchdog.
 *
 * <p>So no ladder route for a seated unit (the hull goes where the order says, not its rider), nor
 * across any unloaded chunk; {@code TacticalNavigationHelper} then falls through to vanilla
 * pathfinding, which only ever reads loaded chunks.
 */
@Mixin(targets = "net.nekoyuni.SimpleEnemyMod.entity.ai.navigation.TacticalNavigationHelper")
public abstract class MixinSemLadderRoute {

    /** SEM's {@code LadderRouteNavigator.SEARCH_MARGIN}. */
    private static final int SEARCH_MARGIN = 24;

    @Inject(method = "tryLadderRoute", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void tacz_sewv$noLadderRouteIntoUnloaded(Mob mob, Vec3 target, double speed,
                                                          CallbackInfoReturnable<Boolean> cir) {
        if (mob.isPassenger()) {
            cir.setReturnValue(false);
            return;
        }
        int minX = (int) Math.floor(Math.min(mob.getX(), target.x)) - SEARCH_MARGIN;
        int minZ = (int) Math.floor(Math.min(mob.getZ(), target.z)) - SEARCH_MARGIN;
        int maxX = (int) Math.ceil(Math.max(mob.getX(), target.x)) + SEARCH_MARGIN;
        int maxZ = (int) Math.ceil(Math.max(mob.getZ(), target.z)) + SEARCH_MARGIN;
        // ponytail: one hasChunk per chunk in the box (stops at the first miss); fine at ordinary
        // order distances, cache per target if a profile ever shows it.
        if (!mob.level().hasChunksAt(minX, minZ, maxX, maxZ)) cir.setReturnValue(false);
    }
}
