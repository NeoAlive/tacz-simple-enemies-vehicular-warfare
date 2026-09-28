package com.neoalive.tacz_sewv.client.skin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;

/**
 * Client mirror of the server's {@code sewv:camo} tag, per entity id, synced by
 * {@link com.neoalive.tacz_sewv.network.PacketEntityCamo}. Absent = the legacy random pick.
 */
@OnlyIn(Dist.CLIENT)
public final class CamoClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ConcurrentHashMap<Integer, Integer> CAMO = new ConcurrentHashMap<>();
    /** Missing-art combos already reported — the renderers ask every frame. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private CamoClient() {
    }

    public static void put(int entityId, int camo) {
        if (camo < 0) {
            CAMO.remove(entityId);
        } else {
            CAMO.put(entityId, camo);
        }
    }

    /** Synced camo number, or {@code -1} when the entity has none. */
    public static int get(int entityId) {
        return CAMO.getOrDefault(entityId, -1);
    }

    public static void clearAll() {
        CAMO.clear();
        WARNED.clear();
    }

    /** One WARN per distinct {@code what}: camo was asked for, the art is missing, legacy pick used. */
    static void warnMissing(String what) {
        if (WARNED.add(what)) {
            LOGGER.warn("[sewv-camo] no art for {} — falling back to the random pick", what);
        }
    }
}
