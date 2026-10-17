package com.neoalive.tacz_sewv.client.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.VehiclePackPresets;
import com.neoalive.tacz_sewv.util.VehiclePackPresets.Pack;
import com.neoalive.tacz_sewv.util.WorldVehiclePools.Category;

/**
 * Applies a pack preset to the pool editor's local snapshot. Does not touch SavedData until Save.
 */
public final class PoolPresetApply {

    public enum Mode {
        NONE, ADD, REPLACE;

        public Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private PoolPresetApply() {}

    /**
     * Mutates {@code pools} in place. No-op when mode is {@link Mode#NONE}. Categories the pack
     * does not define are left untouched on Replace; Add only unions non-empty curated lists.
     */
    public static void apply(Mode mode, Pack pack, Map<TankFaction, Map<Category, List<String>>> pools) {
        if (mode == Mode.NONE || pools == null) return;
        Map<TankFaction, Map<Category, List<String>>> tables = VehiclePackPresets.tables(pack);
        for (TankFaction faction : TankFaction.values()) {
            for (Category category : Category.values()) {
                List<String> curated = tables.get(faction).get(category);
                if (curated.isEmpty()) continue;
                List<String> dest = pools.get(faction).get(category);
                if (mode == Mode.REPLACE) {
                    dest.clear();
                    dest.addAll(curated);
                } else {
                    for (String id : curated) {
                        if (!dest.contains(id)) dest.add(id);
                    }
                }
            }
        }
    }

    /** Deep-copy helper for tests / callers that need an isolated map. */
    public static Map<TankFaction, Map<Category, List<String>>> copyOf(
            Map<TankFaction, Map<Category, List<String>>> src) {
        Map<TankFaction, Map<Category, List<String>>> out = new java.util.EnumMap<>(TankFaction.class);
        for (TankFaction f : TankFaction.values()) {
            Map<Category, List<String>> byCat = new java.util.EnumMap<>(Category.class);
            for (Category c : Category.values()) {
                byCat.put(c, new ArrayList<>(src.get(f).get(c)));
            }
            out.put(f, byCat);
        }
        return out;
    }
}
