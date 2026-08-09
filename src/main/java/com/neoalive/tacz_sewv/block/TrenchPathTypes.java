package com.neoalive.tacz_sewv.block;

import net.minecraft.world.level.pathfinder.BlockPathTypes;

/**
 * Preferred footing for on-foot SEM pathfinding. Must be {@link #bootstrap() bootstrapped}
 * during mod construction — {@link BlockPathTypes#create} grows the enum, and
 * {@code Mob.pathfindingMalus[]} is sized in the Mob constructor from {@code values().length}.
 * Creating the type later AIOOBEs on {@code getPathfindingMalus}.
 */
public final class TrenchPathTypes {

    public static final BlockPathTypes TRENCH = BlockPathTypes.create("tacz_sewv_trench", 0.0F);

    /** Open-ground cost for SEM infantry — higher than {@link #TRENCH}'s 0. */
    public static final float OPEN_GROUND_MALUS = 2.0F;

    private TrenchPathTypes() {}

    /** Force class init (and thus {@link #TRENCH} create) before any Mob is constructed. */
    public static void bootstrap() {
        // touch the field so the class initializer has definitely run
        if (TRENCH == null) throw new IllegalStateException("TrenchPathTypes failed to init");
    }
}
