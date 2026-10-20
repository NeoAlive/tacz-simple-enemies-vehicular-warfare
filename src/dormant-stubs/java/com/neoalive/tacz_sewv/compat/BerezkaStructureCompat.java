// NeoForge port branch: DORMANT-INTEGRATION STUB. The real class (same path under src/main/java)
// is excluded from compilation by dormantSources in build.gradle. Same public surface, constant
// "absent" answers; selfCheckDormantStubs keeps the two in step.
package com.neoalive.tacz_sewv.compat;

import net.minecraft.world.level.Level;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

public final class BerezkaStructureCompat {

    public static final String MODID = "berezka_api";

    private BerezkaStructureCompat() {}

    public static void register() {
    }

    public static boolean refusesOnJoin(Level level, AbstractUnit unit) {
        return false;
    }

    public static void clearGraceBoxes() {
    }
}
