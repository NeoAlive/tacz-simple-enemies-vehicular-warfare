package com.neoalive.tacz_sewv.map;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.entity.ai.support.PathwaySupport;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketPatrolVehicle;
import com.neoalive.tacz_sewv.network.PacketPreferredPathwaysSync;

/**
 * Player-owned preferred pathways, dimension-bound, stored in overworld {@link SavedData}.
 */
public class PreferredPathwayData extends SavedData {

    public static final int MAX_PATHS_PER_DIMENSION = 16;

    private static final String DATA_NAME = "tacz_sewv_preferred_pathways";

    /** player → dimension → pathId → waypoints */
    private final Map<UUID, Map<ResourceKey<Level>, Map<String, List<BlockPos>>>> store = new HashMap<>();

    /** Per-player catalog views — invalidated on any write for that player. */
    private static final Map<UUID, Map<ResourceKey<Level>, PathCatalog>> CATALOG_CACHE = new HashMap<>();

    public PreferredPathwayData() {}

    public static void clearCatalogCache() {
        CATALOG_CACHE.clear();
    }

    public static void clearCatalogCache(UUID player) {
        CATALOG_CACHE.remove(player);
    }

    public static PreferredPathwayData load(CompoundTag nbt) {
        PreferredPathwayData data = new PreferredPathwayData();
        if (!nbt.contains("entries", Tag.TAG_LIST)) return data;
        ListTag entries = nbt.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            UUID player = entry.getUUID("player");
            ResourceLocation dimId = new ResourceLocation(entry.getString("dimension"));
            ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, dimId);
            String pathId = entry.getString("pathId");
            long[] packed = entry.getLongArray("waypoints");
            List<BlockPos> waypoints = new ArrayList<>(packed.length);
            for (long l : packed) waypoints.add(BlockPos.of(l));
            data.put(player, dim, pathId, waypoints);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        ListTag entries = new ListTag();
        for (Map.Entry<UUID, Map<ResourceKey<Level>, Map<String, List<BlockPos>>>> playerEntry
                : this.store.entrySet()) {
            for (Map.Entry<ResourceKey<Level>, Map<String, List<BlockPos>>> dimEntry
                    : playerEntry.getValue().entrySet()) {
                for (Map.Entry<String, List<BlockPos>> pathEntry : dimEntry.getValue().entrySet()) {
                    CompoundTag tag = new CompoundTag();
                    tag.putUUID("player", playerEntry.getKey());
                    tag.putString("dimension", dimEntry.getKey().location().toString());
                    tag.putString("pathId", pathEntry.getKey());
                    List<BlockPos> wps = pathEntry.getValue();
                    long[] packed = new long[wps.size()];
                    for (int i = 0; i < wps.size(); i++) packed[i] = wps.get(i).asLong();
                    tag.putLongArray("waypoints", packed);
                    entries.add(tag);
                }
            }
        }
        nbt.put("entries", entries);
        return nbt;
    }

    private static PreferredPathwayData store(Level level) {
        if (level instanceof ServerLevel serverLevel) {
            ServerLevel overworld = serverLevel.getServer().getLevel(Level.OVERWORLD);
            if (overworld != null) {
                return overworld.getDataStorage().computeIfAbsent(
                        PreferredPathwayData::load, PreferredPathwayData::new, DATA_NAME);
            }
        }
        return new PreferredPathwayData();
    }

    private void put(UUID player, ResourceKey<Level> dim, String pathId, List<BlockPos> waypoints) {
        this.store.computeIfAbsent(player, u -> new HashMap<>())
                .computeIfAbsent(dim, d -> new LinkedHashMap<>())
                .put(pathId, List.copyOf(waypoints));
        CATALOG_CACHE.remove(player);
        setDirty();
    }

    private void remove(UUID player, ResourceKey<Level> dim, String pathId) {
        Map<ResourceKey<Level>, Map<String, List<BlockPos>>> byDim = this.store.get(player);
        if (byDim == null) return;
        Map<String, List<BlockPos>> paths = byDim.get(dim);
        if (paths == null) return;
        paths.remove(pathId);
        if (paths.isEmpty()) byDim.remove(dim);
        if (byDim.isEmpty()) this.store.remove(player);
        CATALOG_CACHE.remove(player);
        setDirty();
    }

    public static PathCatalog forOwner(Level level, UUID player, ResourceKey<Level> dim) {
        return CATALOG_CACHE
                .computeIfAbsent(player, u -> new HashMap<>())
                .computeIfAbsent(dim, d -> loadCatalog(level, player, dim));
    }

    private static PathCatalog loadCatalog(Level level, UUID player, ResourceKey<Level> dim) {
        PreferredPathwayData data = store(level);
        Map<String, List<BlockPos>> paths = data.store
                .getOrDefault(player, Map.of())
                .getOrDefault(dim, Map.of());
        return new PathCatalog(paths);
    }

    public static List<BlockPos> getPath(Level level, UUID player, ResourceKey<Level> dim, String pathId) {
        return forOwner(level, player, dim).waypoints(pathId);
    }

    public static boolean savePath(ServerPlayer player, ResourceKey<Level> dim, String pathId,
                                   List<BlockPos> nodes) {
        if (!PathwaySupport.isValidPathId(pathId)) return false;
        if (nodes.size() > PacketPatrolVehicle.MAX_ROUTE_NODES) {
            nodes = nodes.subList(0, PacketPatrolVehicle.MAX_ROUTE_NODES);
        }
        PreferredPathwayData data = store(player.level());
        Map<String, List<BlockPos>> existing = data.store
                .computeIfAbsent(player.getUUID(), u -> new HashMap<>())
                .computeIfAbsent(dim, d -> new LinkedHashMap<>());
        if (!existing.containsKey(pathId) && existing.size() >= MAX_PATHS_PER_DIMENSION) {
            return false;
        }
        if (nodes.size() < 2) return false;
        data.put(player.getUUID(), dim, pathId, nodes);
        syncTo(player);
        return true;
    }

    public static boolean deletePath(ServerPlayer player, ResourceKey<Level> dim, String pathId) {
        if (!PathwaySupport.isValidPathId(pathId)) return false;
        PreferredPathwayData data = store(player.level());
        data.remove(player.getUUID(), dim, pathId);
        syncTo(player);
        return true;
    }

    public static void syncTo(ServerPlayer player) {
        PreferredPathwayData data = store(player.level());
        Map<ResourceKey<Level>, Map<String, List<BlockPos>>> byDim =
                data.store.getOrDefault(player.getUUID(), Map.of());
        NetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PacketPreferredPathwaysSync(byDim));
    }

    /** Immutable view of one player's paths in one dimension. */
    public record PathCatalog(Map<String, List<BlockPos>> paths) {

        public PathCatalog {
            paths = paths == null ? Map.of() : Collections.unmodifiableMap(paths);
        }

        public boolean isEmpty() {
            return paths.isEmpty();
        }

        public boolean hasPath(String pathId) {
            return paths.containsKey(pathId);
        }

        @javax.annotation.Nullable
        public List<BlockPos> waypoints(String pathId) {
            return paths.get(pathId);
        }
    }
}
