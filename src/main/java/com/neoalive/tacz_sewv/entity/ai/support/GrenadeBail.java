package com.neoalive.tacz_sewv.entity.ai.support;

import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile;
import com.atsuishio.superbwarfare.entity.projectile.HandGrenadeEntity;
import com.atsuishio.superbwarfare.entity.projectile.RgoGrenadeEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.entity.ai.goal.BailOutVehicleGoal;

/**
 * A live frag warns every on-foot {@link AbstractUnit} inside its blast, any faction. The bail
 * goal only runs them away from the grenade. Crews seated in a vehicle are left alone. Smoke is
 * not a frag.
 *
 * <p>The scan waits until the grenade has left the thrower's hand (or the fuse is already short)
 * so a normal toss does not scatter the squad that just threw it.
 */
public final class GrenadeBail {

    private static final double HAND_LEAVE_SQ = 16.0;
    private static final int HAND_LEAVE_TICKS = 10;
    private static final int URGENT_FUSE = 20;

    private GrenadeBail() {}

    public static void warn(FastThrowableProjectile grenade) {
        if (grenade.level().isClientSide()) return;
        if (!(grenade instanceof HandGrenadeEntity || grenade instanceof RgoGrenadeEntity)) return;
        if (grenade.getExploded() || grenade.getLife() <= 0) return;
        if (grenade.tickCount != 1 && grenade.tickCount % 5 != 0) return;

        Entity owner = grenade.getOwner();
        boolean urgent = grenade.getLife() <= URGENT_FUSE;
        boolean leftHand = owner == null
                || grenade.distanceToSqr(owner) > HAND_LEAVE_SQ
                || grenade.tickCount > HAND_LEAVE_TICKS
                || urgent;
        if (!leftHand) return;

        float radius = grenade.getExplosionRadius();
        if (radius < 1.0F) radius = 6.0F;
        double scan = radius + 3.0;
        double clearance = radius + 4.0;
        AABB box = grenade.getBoundingBox().inflate(scan);
        Vec3 threat = grenade.position();
        for (AbstractUnit unit : grenade.level().getEntitiesOfClass(AbstractUnit.class, box,
                Entity::isAlive)) {
            if (unit.distanceToSqr(grenade) > scan * scan) continue;
            BailOutVehicleGoal.requestGrenadeBail(unit, threat, clearance);
        }
    }
}
