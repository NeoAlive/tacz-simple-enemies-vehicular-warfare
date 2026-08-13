package com.neoalive.tacz_sewv.client;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Client store of runway footprints that have <b>passed</b> a clearance check, so the world map can
 * shade the strips a player has actually surveyed.
 *
 * <p>This needs <b>no packet of its own</b>. The clearance reply
 * ({@link com.neoalive.tacz_sewv.network.PacketOpenAirportGui#result}) already carries the runway
 * block, both corners and the cleared flag — everything a footprint is — so the store is filled from
 * the reply handler and the wire format is untouched. The key is the runway block, which is also the
 * registry's key, so re-checking a strip replaces its plot rather than stacking a second one.
 *
 * <p>Keyed on {@code cleared} rather than on the OK status: that also drops the plot when a check
 * fails (the server clears the runway before re-evaluating it) and re-adds it when the editor is
 * merely opened on a strip that is still good, which is what restores the shading after a relog
 * without a sync. It is deliberately not persisted — the runway block is the durable record.
 *
 * <p>Free of Xaero types, like {@link MapTrenchMarkers}, so it is safe to touch without the map mod.
 */
public final class AirportPlots {

    /** A cleared strip's XZ rectangle. Corners are unordered; the reader takes min/max. */
    public record Plot(ResourceKey<Level> dimension, BlockPos runway,
                       int x1, int z1, int x2, int z2) {}

    private static final Map<BlockPos, Plot> PLOTS = new LinkedHashMap<>();

    private AirportPlots() {}

    public static void note(BlockPos runway, ResourceKey<Level> dimension,
                            int x1, int z1, int x2, int z2) {
        PLOTS.put(runway.immutable(), new Plot(dimension, runway.immutable(), x1, z1, x2, z2));
    }

    public static void forget(BlockPos runway) {
        PLOTS.remove(runway);
    }

    public static void clear() {
        PLOTS.clear();
    }

    /** A snapshot, so the map's draw pass can drop a stale plot while iterating. */
    public static Collection<Plot> plots() {
        return PLOTS.isEmpty() ? List.of() : List.copyOf(PLOTS.values());
    }
}
