package com.neoalive.tacz_sewv.loadout;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.server.ServerLifecycleHooks;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * Runtime half of the loadout manager. {@code MixinUnitLoadoutManager} feeds it SEM's raw files at
 * the head of every {@code apply}; this class keeps a pristine copy (provenance for the editor and
 * the source of every re-apply) and lets the world's {@link LoadoutLayer} edit the live map.
 *
 * <p>Startup ordering: SEM's first {@code apply} runs before any level exists, so there is no
 * layer to read yet — it passes through, and {@link #reapply} runs once on {@code ServerStartedEvent}.
 * A later {@code /reload} has levels, so it merges inline.
 */
public final class LoadoutManager {

    public static final String EXTENDED_ID = "simple_enemy_extended";
    public static final String CONFIG_ID = "simple_enemy_mod_config";
    /** Pack id of berezka_api's shared virtual pack — the one the config mod registers its file in. */
    public static final String BEREZKA_PACK = "berezka_api_data";
    /** SEM's folder under {@code data/<ns>/}; the raw keys have it stripped, so provenance re-adds it. */
    private static final String SEM_FOLDER = "unit_loadouts";

    /** One loadout as SEM's files supplied it, with where it came from. */
    public record Inherited(String fileId, String name, String packId, LoadoutRow row) {}

    private static volatile Map<String, JsonElement> snapshot = Map.of();
    private static volatile Map<String, String> packOf = Map.of();
    private static volatile IReapplier live;
    private static volatile ResourceManager resources;
    private static volatile Map<TankFaction, LoadoutMerge.SbwPool> sbwPools = Map.of();
    /** Main thread only: true while {@link #reapply} is inside SEM's {@code apply}. */
    private static boolean reapplying;

    private LoadoutManager() {}

    /** False until SEM's listener has run at least once with our mixin applied. */
    public static boolean hookActive() {
        return live != null;
    }

    public static boolean extendedPresent() {
        return loaded(EXTENDED_ID);
    }

    public static boolean configModPresent() {
        return loaded(CONFIG_ID);
    }

    private static boolean loaded(String modId) {
        ModList list = ModList.get();
        return list != null && list.isLoaded(modId);
    }

    /** Mixin entry, HEAD of {@code UnitLoadoutManager.apply}. Mutates {@code files} in place. */
    public static void onApply(Object self, Map<ResourceLocation, JsonElement> files, ResourceManager rm) {
        if (reapplying) return;
        Map<String, JsonElement> snap = new LinkedHashMap<>();
        Map<String, String> packs = new HashMap<>();
        for (var e : files.entrySet()) {
            String id = e.getKey().toString();
            snap.put(id, e.getValue().deepCopy());
            var res = rm.getResource(new ResourceLocation(e.getKey().getNamespace(),
                    SEM_FOLDER + "/" + e.getKey().getPath() + ".json"));
            res.ifPresent(r -> packs.put(id, r.sourcePackId()));
        }
        snapshot = snap;
        packOf = packs;
        resources = rm;
        live = self instanceof IReapplier r ? r : null;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        Map<String, JsonElement> merged = server == null ? null : mergedFor(server);
        if (merged != null) replace(files, merged);
        TaczSewv.LOGGER.info("[sewv-loadout] captured {} SEM loadout files (SEM Extended: {}, Config mod: {}); layer {}",
                snap.size(), extendedPresent(), configModPresent(),
                server == null ? "deferred to server start" : merged != null ? "merged" : "not managed");
    }

    /** Push the current layer through SEM's own {@code apply}. Main thread. */
    public static boolean reapply(MinecraftServer server) {
        IReapplier target = live;
        if (target == null) return false;
        Map<String, JsonElement> merged = mergedFor(server);
        Map<ResourceLocation, JsonElement> files = new HashMap<>();
        replace(files, merged != null ? merged : snapshot);
        reapplying = true;
        try {
            target.sewv$reapply(files, resources);
        } catch (RuntimeException e) {
            TaczSewv.LOGGER.error("[sewv-loadout] re-apply failed; SEM's pool may be stale until /reload", e);
            return false;
        } finally {
            reapplying = false;
        }
        TaczSewv.LOGGER.info("[sewv-loadout] re-applied through SEM ({} files, layer {})",
                files.size(), merged != null ? "merged" : "not managed");
        return true;
    }

    /** The SBW half of a faction's pool ({@code NpcSbwWeapon} reads it at spawn); PMC is always empty. */
    public static LoadoutMerge.SbwPool sbwPool(TankFaction faction) {
        return sbwPools.getOrDefault(faction, LoadoutMerge.SbwPool.EMPTY);
    }

    /**
     * Null = nothing is managed, so SEM's own files must be left exactly as they are. Also
     * refreshes {@link #sbwPools}, since the SBW share depends on what SEM ends up rolling.
     */
    @Nullable
    private static Map<String, JsonElement> mergedFor(MinecraftServer server) {
        LoadoutLayer layer = LoadoutLayer.get(server);
        if (layer == null) return null;
        Map<String, LoadoutMerge.Faction> layers = new HashMap<>();
        for (TankFaction faction : TankFaction.values()) {
            String folder = LoadoutLayer.folderOf(faction);
            LoadoutMerge.Faction f = layer.faction(folder);
            if (!f.managed) continue;
            f.rows.removeIf(r -> {
                boolean drop = !LoadoutValidator.gunUsable(r.gunId)
                        || (faction == TankFaction.PMC && WeaponCatalogSource.isSbwGun(r.gunId));
                if (drop) TaczSewv.LOGGER.warn("[sewv-loadout] {}/{}: unusable gun {}, skipped", folder, r.name, r.gunId);
                return drop;
            });
            layers.put(folder, f);
        }
        if (layers.isEmpty()) {
            sbwPools = Map.of();
            return null;
        }
        boolean extended = extendedPresent();
        Map<String, JsonElement> merged = LoadoutMerge.merge(snapshot, layers, extended, WeaponCatalogSource::isSbwGun);
        Map<TankFaction, LoadoutMerge.SbwPool> pools = new EnumMap<>(TankFaction.class);
        for (TankFaction faction : TankFaction.values()) {
            String folder = LoadoutLayer.folderOf(faction);
            LoadoutMerge.SbwPool pool = LoadoutMerge.sbwPool(merged, folder, layers.get(folder), extended,
                    WeaponCatalogSource::isSbwGun);
            if (!pool.rows().isEmpty()) pools.put(faction, pool);
        }
        sbwPools = pools;
        return merged;
    }

    private static void replace(Map<ResourceLocation, JsonElement> into, Map<String, JsonElement> from) {
        into.clear();
        from.forEach((k, v) -> into.put(new ResourceLocation(k), v));
    }

    /** Everything SEM's files hold for one faction folder, before our layer touches it. */
    public static List<Inherited> inherited(String folder) {
        List<Inherited> out = new ArrayList<>();
        for (var e : snapshot.entrySet()) {
            if (!LoadoutMerge.folderOf(e.getKey()).equals(folder) || !e.getValue().isJsonObject()) continue;
            JsonObject root = e.getValue().getAsJsonObject();
            if (!root.has("loadouts") || !root.get("loadouts").isJsonObject()) continue;
            for (var le : root.getAsJsonObject("loadouts").entrySet()) {
                if (!le.getValue().isJsonObject()) continue;
                LoadoutRow row = LoadoutRow.fromJson(le.getKey(), le.getValue().getAsJsonObject());
                if (row != null) {
                    out.add(new Inherited(e.getKey(), le.getKey(), packOf.getOrDefault(e.getKey(), ""), row));
                }
            }
        }
        return out;
    }
}
