package com.neoalive.tacz_sewv.client.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import com.atsuishio.superbwarfare.item.HandGrenade;
import com.atsuishio.superbwarfare.item.RgoGrenade;
import com.atsuishio.superbwarfare.item.projectile.Blu43MineItem;
import com.atsuishio.superbwarfare.item.projectile.ClaymoreMineItem;
import com.atsuishio.superbwarfare.item.projectile.EDDItem;
import com.atsuishio.superbwarfare.item.projectile.M18SmokeGrenadeItem;
import com.atsuishio.superbwarfare.item.projectile.Tm62Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * Item-id autocomplete for the GRENADE and MINE pools.
 * Vehicle catalogs are entity types; grenade pools store item ids.
 */
public final class GrenadePoolCatalog {

    private static final List<String> BUILTIN = List.of(
            "superbwarfare:hand_grenade",
            "superbwarfare:rgo_grenade",
            "superbwarfare:m18_smoke_grenade");

    private static final List<String> MINE_BUILTIN = List.of(
            "superbwarfare:tm_62",
            "superbwarfare:blu_43_mine",
            "superbwarfare:claymore_mine",
            "superbwarfare:edd");

    private static volatile List<String> ids = List.of();
    private static volatile List<String> mineIds = List.of();
    private static volatile boolean ready = false;

    private GrenadePoolCatalog() {}

    public static void ensureLoaded() {
        if (ready) return;
        build();
    }

    public static List<String> ids() {
        ensureLoaded();
        return ids;
    }

    /** Item-id autocomplete for the MINE pool: SBW's mine items. Addon mines are typed in by id. */
    public static List<String> mineIds() {
        ensureLoaded();
        return mineIds;
    }

    /** Fixed defaults plus any registered SBW throwable grenade items. */
    private static void build() {
        TreeSet<String> set = new TreeSet<>(BUILTIN);
        try {
            for (Item item : BuiltInRegistries.ITEM) {
                if (!(item instanceof HandGrenade)
                        && !(item instanceof RgoGrenade)
                        && !(item instanceof M18SmokeGrenadeItem)) {
                    continue;
                }
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
                if (key != null) set.add(key.toString());
            }
        } catch (Throwable ignored) {
            // Registry may still be empty on first open; builtins alone still work.
        }
        ids = List.copyOf(set);
        TreeSet<String> mines = new TreeSet<>(MINE_BUILTIN);
        try {
            for (Item item : BuiltInRegistries.ITEM) {
                if (!(item instanceof Tm62Item) && !(item instanceof Blu43MineItem)
                        && !(item instanceof ClaymoreMineItem) && !(item instanceof EDDItem)) {
                    continue;
                }
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
                if (key != null) mines.add(key.toString());
            }
        } catch (Throwable ignored) {
            // Same as above: the builtins still work.
        }
        mineIds = List.copyOf(mines);
        ready = true;
    }

    public static List<String> mergedWith(List<String> extra) {
        ensureLoaded();
        if (extra == null || extra.isEmpty()) return ids();
        TreeSet<String> merged = new TreeSet<>(ids);
        merged.addAll(extra);
        return List.copyOf(merged);
    }

    public static String suggest(String typed, List<String> catalog, List<String> excluded) {
        return VehiclePoolCatalog.suggest(typed, catalog, excluded);
    }

    public static String completionSuffix(String typed, String suggestion) {
        return VehiclePoolCatalog.completionSuffix(typed, suggestion);
    }

    /** Case-insensitive contains filter for the add-from-catalog list. */
    public static List<String> filter(String query, List<String> catalog, List<String> excluded) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String id : catalog) {
            if (excluded.contains(id)) continue;
            if (!q.isEmpty() && !id.toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(id);
        }
        return out;
    }
}
