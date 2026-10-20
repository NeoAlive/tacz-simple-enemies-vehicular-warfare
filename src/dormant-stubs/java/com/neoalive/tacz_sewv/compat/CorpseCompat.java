// NeoForge port branch: DORMANT-INTEGRATION STUB. The real class (same path under src/main/java)
// is excluded from compilation by dormantSources in build.gradle. Same public surface, constant
// "absent" answers; selfCheckDormantStubs keeps the two in step.
package com.neoalive.tacz_sewv.compat;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public final class CorpseCompat {

    public static final String MODID = "corpse";

    private static final Logger LOGGER = LogUtils.getLogger();

    private CorpseCompat() {}

    public static boolean isLoaded() {
        return false;
    }

    public static void reportAvailability() {
        LOGGER.info("Corpse soft-compat dormant on this build (not ported to NeoForge yet)");
    }
}
