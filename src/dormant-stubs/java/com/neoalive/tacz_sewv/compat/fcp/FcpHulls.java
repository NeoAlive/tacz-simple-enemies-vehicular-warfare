// NeoForge port branch: DORMANT-INTEGRATION STUB. The real class (same path under src/main/java)
// is excluded from compilation by dormantSources in build.gradle. FcpCompat stays real and asks
// this only behind supported(), which a 1.21 install never reaches.
package com.neoalive.tacz_sewv.compat.fcp;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.SimpleContainer;

final class FcpHulls {

    private FcpHulls() {}

    static boolean hasHold(VehicleEntity hull) {
        return false;
    }

    static SimpleContainer hold(VehicleEntity hull) {
        return new SimpleContainer(0);
    }

    static boolean engineBlocked(VehicleEntity hull) {
        return false;
    }

    static void startEngineNow(VehicleEntity hull) {}

    static void holdEngineStart(VehicleEntity hull) {}

    static void fillCombatLoad(VehicleEntity hull) {}

    static boolean isArtillery(VehicleEntity hull) {
        return false;
    }
}
