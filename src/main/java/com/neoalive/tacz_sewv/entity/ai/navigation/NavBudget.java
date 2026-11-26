package com.neoalive.tacz_sewv.entity.ai.navigation;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;

/**
 * Infantry {@code moveTo} with a node budget sized to the trip.
 *
 * <p>Vanilla searches with {@code FOLLOW_RANGE * 16} nodes, 1536 for a SEM unit, whatever the distance,
 * so a short walk to an awkward or unreachable spot burns the whole budget on every repath. The
 * multiplier is set for this one search and reset at once, so vanilla's own later recomputes keep
 * the full budget.
 */
public final class NavBudget {

    static final double NODES_PER_BLOCK = 24.0;
    static final double NODES_BASE = 128.0;
    static final float MIN_MULT = 0.25F;

    private NavBudget() {}

    /** Fraction of the follow-range budget a search over {@code distance} blocks gets. */
    public static float multiplier(double distance, double followRange) {
        double full = Math.max(1.0, followRange) * 16.0;
        double wanted = Math.max(0.0, distance) * NODES_PER_BLOCK + NODES_BASE;
        return (float) Math.max(MIN_MULT, Math.min(1.0, wanted / full));
    }

    public static boolean moveTo(Mob mob, Entity target, double speed) {
        PathNavigation nav = mob.getNavigation();
        nav.setMaxVisitedNodesMultiplier(multiplier(mob.distanceTo(target), followRange(mob)));
        try {
            return nav.moveTo(target, speed);
        } finally {
            nav.resetMaxVisitedNodesMultiplier();
        }
    }

    public static boolean moveTo(Mob mob, double x, double y, double z, double speed) {
        PathNavigation nav = mob.getNavigation();
        double dx = x - mob.getX(), dy = y - mob.getY(), dz = z - mob.getZ();
        nav.setMaxVisitedNodesMultiplier(multiplier(Math.sqrt(dx * dx + dy * dy + dz * dz), followRange(mob)));
        try {
            return nav.moveTo(x, y, z, speed);
        } finally {
            nav.resetMaxVisitedNodesMultiplier();
        }
    }

    private static double followRange(Mob mob) {
        return mob.getAttributeValue(Attributes.FOLLOW_RANGE);
    }
}
