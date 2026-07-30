package com.neoalive.tacz_sewv.compat;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

/**
 * Softcompat gate for FCP wheeled mortar vehicles ({@code toyota_hilux_mortar},
 * {@code stryker_mortar}).
 *
 * <p>Enabled only when FCP is loaded <b>and</b> both entity types are registered — the
 * practical null-safe stand-in for both vehicle datapack JSONs shipping with that FCP
 * build. A no-FCP install or a pre-mortar FCP build leaves this inert; no FCP class is
 * ever referenced.
 */
public final class FcpMortarCompat {

    public static final String MODID = "fcp";

    public static final String HILUX_ID = "fcp:toyota_hilux_mortar";
    public static final String STRYKER_ID = "fcp:stryker_mortar";

    private static final String[] IDS = {HILUX_ID, STRYKER_ID};

    private static boolean resolved;
    private static boolean available;

    private FcpMortarCompat() {}

    public static boolean present() {
        return ModList.get().isLoaded(MODID);
    }

    /**
     * True when this install has FCP <b>and</b> both mortar vehicle entity types.
     * Missing either id keeps the whole compat off.
     */
    public static boolean available() {
        resolve();
        return available;
    }

    public static boolean isMortarHull(@Nullable VehicleEntity hull) {
        if (hull == null || !available()) return false;
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(hull.getType());
        return key != null && isMortarHull(key.toString());
    }

    public static boolean isMortarHull(@Nullable String entityId) {
        if (entityId == null || !available()) return false;
        for (String id : IDS) {
            if (id.equals(entityId)) return true;
        }
        return false;
    }

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        if (!present()) {
            available = false;
            return;
        }
        for (String id : IDS) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null || !ForgeRegistries.ENTITY_TYPES.containsKey(rl)) {
                available = false;
                return;
            }
        }
        available = true;
    }
}
