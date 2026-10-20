// NeoForge port branch: DORMANT-INTEGRATION STUB. The real class (same path under src/main/java)
// is excluded from compilation by dormantSources in build.gradle. Same public surface, constant
// "absent" answers; selfCheckDormantStubs keeps the two in step.
package com.neoalive.tacz_sewv.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class EnhancedFallingTreesFeller {

    private EnhancedFallingTreesFeller() {}

    public static void clearCache() {
    }

    public static boolean isFellable(BlockGetter level, BlockPos pos, BlockState state) {
        return false;
    }

    public static boolean isFoliage(BlockState state) {
        return false;
    }

    public static boolean tryFell(Level level, BlockPos pos, Entity owner) {
        return false;
    }
}
