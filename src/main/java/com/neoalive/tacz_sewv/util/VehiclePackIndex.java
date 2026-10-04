package com.neoalive.tacz_sewv.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.VehiclePackPresets.Pack;

/**
 * Which known vehicle packs are present this session, plus assessment counts for the pool editor.
 * Built once after registries are live (client login warm / first editor open).
 */
public final class VehiclePackIndex {

    public record Assessment(int ru, int us, int pmc, int live, int total) {}

    public record Entry(Pack pack, Assessment assessment) {}

    private static volatile List<Entry> present = List.of();
    private static volatile boolean loaded;

    private VehiclePackIndex() {}

    public static void ensureLoaded() {
        if (loaded) return;
        rebuild();
    }

    public static void rebuild() {
        List<Entry> out = new ArrayList<>();
        for (Pack pack : VehiclePackPresets.packs()) {
            if (!isPresent(pack)) continue;
            out.add(new Entry(pack, assess(pack)));
        }
        present = Collections.unmodifiableList(out);
        loaded = true;
    }

    /** Present packs in declaration order (empty until {@link #ensureLoaded}). */
    public static List<Entry> present() {
        ensureLoaded();
        return present;
    }

    public static boolean isPresent(Pack pack) {
        // Superb Warfare is a hard dependency; still check ModList so a broken install hides the button.
        return ModList.get().isLoaded(pack.modId);
    }

    public static Assessment assess(Pack pack) {
        int ru = VehiclePackPresets.factionCount(pack, TankFaction.RU);
        int us = VehiclePackPresets.factionCount(pack, TankFaction.US);
        int pmc = VehiclePackPresets.factionCount(pack, TankFaction.PMC);
        Set<String> ids = VehiclePackPresets.allIds(pack);
        int live = 0;
        for (String id : ids) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null && ForgeRegistries.ENTITY_TYPES.containsKey(rl)) live++;
        }
        return new Assessment(ru, us, pmc, live, ids.size());
    }

    /** Snapshot of faction counts used by the self-check (no registry). */
    public static Map<TankFaction, Integer> factionCounts(Pack pack) {
        Map<TankFaction, Integer> out = new EnumMap<>(TankFaction.class);
        for (TankFaction f : TankFaction.values()) {
            out.put(f, VehiclePackPresets.factionCount(pack, f));
        }
        return out;
    }
}
