package com.neoalive.tacz_sewv.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Client cache of the player's saved preferred pathways, fed by {@link
 * com.neoalive.tacz_sewv.network.PacketPreferredPathwaysSync}.
 */
public final class PreferredPathwaysClient {

    private static Map<ResourceKey<Level>, Map<String, List<BlockPos>>> pathsByDimension = Map.of();

    private PreferredPathwaysClient() {}

    public static void apply(Map<ResourceKey<Level>, Map<String, List<BlockPos>>> incoming) {
        Map<ResourceKey<Level>, Map<String, List<BlockPos>>> copy = new HashMap<>();
        for (Map.Entry<ResourceKey<Level>, Map<String, List<BlockPos>>> e : incoming.entrySet()) {
            copy.put(e.getKey(), Map.copyOf(e.getValue()));
        }
        pathsByDimension = Collections.unmodifiableMap(copy);
    }

    public static Map<String, List<BlockPos>> forDimension(ResourceKey<Level> dimension) {
        return pathsByDimension.getOrDefault(dimension, Map.of());
    }

    public static void clear() {
        pathsByDimension = Map.of();
    }
}
