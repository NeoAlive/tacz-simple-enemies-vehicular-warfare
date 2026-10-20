// NeoForge port branch: DORMANT-INTEGRATION STUB. The real class (same path under src/main/java)
// is excluded from compilation by dormantSources in build.gradle. Same public surface, constant
// "absent" answers; selfCheckDormantStubs keeps the two in step.
package com.neoalive.tacz_sewv.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

public final class PlayerReviveCompat {

    public static final String MODID = "playerrevive";

    private static final Logger LOGGER = LogUtils.getLogger();

    private PlayerReviveCompat() {}

    public static boolean isLoaded() {
        return false;
    }

    public static void reportAvailability() {
        LOGGER.info("PlayerReviveMod soft-compat dormant on this build (not ported to NeoForge yet)");
    }

    public static boolean isDowned(Player player) {
        return false;
    }

    public static void revive(Player player) {
    }
}
