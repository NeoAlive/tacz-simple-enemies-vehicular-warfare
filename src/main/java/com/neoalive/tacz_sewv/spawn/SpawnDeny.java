package com.neoalive.tacz_sewv.spawn;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import com.neoalive.tacz_sewv.zone.SpawnZones;
import com.neoalive.tacz_sewv.zone.ZoneType;

/**
 * Why the latest spawn attempt on this server thread returned nothing. Spawn methods set it on the
 * way out; {@code /sewv spawn} and a forced SEM event read it. Ambient rolls set it too and nobody
 * reads that — the next command or force clears first.
 *
 * <p>ponytail: one server-thread slot. A second spawn overlapping this one would need its own.
 */
public final class SpawnDeny {

    public enum Why {
        GRACE("grace"),
        AMBIENT("ambient"),
        NOZONE("nozone"),
        PRIORITY("priority"),
        COLONY("colony"),
        GAMERULE("gamerule"),
        POOL("pool"),
        SPACE("space"),
        WATER("water"),
        UNLOADED("unloaded"),
        CREW("crew"),
        PLAYER_ONLY("player_only"),
        NOT_VEHICLE("not_vehicle"),
        SPOT("spot");

        final String id;

        Why(String id) {
            this.id = id;
        }
    }

    @Nullable
    private static Why why;

    @Nullable
    private static String arg;

    private SpawnDeny() {}

    public static void clear() {
        why = null;
        arg = null;
    }

    public static boolean unset() {
        return why == null;
    }

    public static void set(Why next) {
        why = next;
        arg = null;
    }

    public static void set(Why next, String detail) {
        why = next;
        arg = detail;
    }

    /** The chunk itself is refused: no-zone, or neutral while only prioritization is allowed. */
    public static void zoneAt(ServerLevel level, BlockPos pos) {
        set(SpawnZones.typeAt(level, pos) == ZoneType.NOZONE ? Why.NOZONE : Why.PRIORITY);
    }

    /**
     * {@code findClearSpawn} found nothing. A blocked legal column beats a dry one, which beats a
     * zone refusal of the requested chunk. Anything else is unloaded terrain.
     */
    public static void placementMiss(ServerLevel level, BlockPos origin, boolean blocked, boolean dry, boolean zone) {
        if (blocked) set(Why.SPACE);
        else if (dry) set(Why.WATER);
        else if (zone && !SpawnZones.allows(level, origin)) zoneAt(level, origin);
        else set(Why.UNLOADED);
    }

    public static Component component() {
        String key = why == null ? "command.tacz_sewv.spawn.fail" : "command.tacz_sewv.spawn.fail." + why.id;
        return arg == null ? Component.translatable(key) : Component.translatable(key, arg);
    }
}
