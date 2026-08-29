package com.neoalive.tacz_sewv.entity.ai.sensor;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Three-way block sight for contacts: clear engage, foliage-only (uncertain — something is
 * there but identity is not), or hard-blocked.
 *
 * <p>Vanilla {@code hasLineOfSight} is binary and treats leaves as solid. Foliage-only is the
 * {@code DISTANT_CONTACT} case: feed {@link OuterRingAwareness}, never {@code setTarget}.
 */
public final class ContactSight {

    public enum Kind {
        /** No block between eyes — may still be hull-occluded by {@code hasLineOfSight}. */
        CLEAR,
        /** Only {@link BlockTags#LEAVES} on the ray — presence without a lock. */
        UNCERTAIN,
        /** At least one non-leaf collider on the ray. */
        BLOCKED
    }

    private static final int MAX_SKIPS = 24;
    private static final double NUDGE = 0.05;

    private ContactSight() {}

    public static Kind between(LivingEntity from, LivingEntity to) {
        return between(from.level(), from.getEyePosition(), to.getEyePosition(), from);
    }

    public static Kind between(Level level, Vec3 from, Vec3 to, @Nullable Entity context) {
        Vec3 cursor = from;
        Vec3 span = to.subtract(from);
        double len = span.length();
        if (len < 1.0E-4) return Kind.CLEAR;
        Vec3 step = span.scale(1.0 / len);
        boolean foliage = false;

        for (int i = 0; i < MAX_SKIPS; i++) {
            HitResult hit = level.clip(new ClipContext(
                    cursor, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, context));
            if (hit.getType() == HitResult.Type.MISS) {
                return foliage ? Kind.UNCERTAIN : Kind.CLEAR;
            }
            if (!(hit instanceof BlockHitResult blockHit)) return Kind.BLOCKED;
            BlockState state = level.getBlockState(blockHit.getBlockPos());
            if (!state.is(BlockTags.LEAVES)) return Kind.BLOCKED;
            foliage = true;
            cursor = blockHit.getLocation().add(step.scale(NUDGE));
            if (cursor.distanceToSqr(to) < NUDGE * NUDGE) return Kind.UNCERTAIN;
        }
        return Kind.BLOCKED;
    }
}
