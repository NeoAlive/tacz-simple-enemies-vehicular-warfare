// NeoForge port branch: DORMANT-INTEGRATION STUB. The real class (same path under src/main/java)
// is excluded from compilation by dormantSources in build.gradle. Same public surface, constant
// "absent" answers; selfCheckDormantStubs keeps the two in step.
package com.neoalive.tacz_sewv.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;

public final class MineColoniesCompat {

    public static final String MODID = "minecolonies";

    private MineColoniesCompat() {}

    public static boolean present() {
        return false;
    }

    public static void register(IEventBus modEventBus) {
    }

    public static boolean inAnyColony(Level level, BlockPos pos) {
        return false;
    }
}
