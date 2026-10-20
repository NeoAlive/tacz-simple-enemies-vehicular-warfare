package com.neoalive.tacz_sewv.block;

import net.minecraft.world.level.pathfinder.PathType;

/**
 * Preferred footing for on-foot SEM pathfinding.
 *
 * <p>NeoForge port: Forge grew the enum with {@code PathType.create}; NeoForge 1.21.1's
 * {@link PathType} is not an extensible enum, so {@link #TRENCH} borrows {@link PathType#COCOA}.
 * That type is the right carrier: {@code WalkNodeEvaluator} returns it only for cocoa pods, applies
 * no special handling to it beyond its own malus slot, and its default malus is 0 — exactly what
 * the custom type was. The cost is that a unit treats a cocoa pod as trench footing, which never
 * matters. {@link #bootstrap()} is kept so call sites read as on main.
 */
public final class TrenchPathTypes {

    public static final PathType TRENCH = PathType.COCOA;

    /** Open-ground cost for SEM infantry — higher than {@link #TRENCH}'s 0. */
    public static final float OPEN_GROUND_MALUS = 2.0F;

    private TrenchPathTypes() {}

    /** No-op on this branch; see the class comment. */
    public static void bootstrap() {
    }
}
