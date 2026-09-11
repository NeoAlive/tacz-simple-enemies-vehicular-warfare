package com.neoalive.tacz_sewv.compat;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Softcompat gate for FCP crew-served emplacements.
 *
 * <p>FCP emplacements have no vehicle hold ({@code VehicleContainerType: Empty}). Magazine guns
 * reload only while a <em>Player</em> is aboard; single-shot guns (ZiS-3 / TOW / Kornet) load only
 * via player right-click. SEM crews need an AI reload path — see
 * {@link com.neoalive.tacz_sewv.entity.ai.support.TowSupport}.
 *
 * <p>No FCP class is referenced — only registry ids — so an install without FCP never classloads it.
 */
public final class FcpEmplacementCompat {

    public static final String MODID = "fcp";

    /** Single-shot / hand-load (ZiS-3, TOW, Kornet). */
    private static final String[] MANUAL_IDS = {
            "fcp:empl_zis3",
            "fcp:empl_tow",
            "fcp:empl_kornet",
    };

    /** Magazine-fed (M2, MG3, DShK, Mk19, AGS-17). */
    private static final String[] MAGAZINE_IDS = {
            "fcp:empl_m2",
            "fcp:empl_mg3",
            "fcp:empl_dshk",
            "fcp:empl_mk19",
            "fcp:empl_ags17",
    };

    private FcpEmplacementCompat() {
    }

    public static boolean present() {
        return ModList.get().isLoaded(MODID);
    }

    /** Any FCP emplacement that needs AI magazine / rail loading. */
    public static boolean needsAiReload(@Nullable VehicleEntity hull) {
        String id = entityId(hull);
        return isManual(id) || isMagazine(id);
    }

    public static boolean isManual(@Nullable VehicleEntity hull) {
        return isManual(entityId(hull));
    }

    public static boolean isMagazine(@Nullable VehicleEntity hull) {
        return isMagazine(entityId(hull));
    }

    public static boolean isManual(@Nullable String entityId) {
        return present() && matches(entityId, MANUAL_IDS);
    }

    public static boolean isMagazine(@Nullable String entityId) {
        return present() && matches(entityId, MAGAZINE_IDS);
    }

    @Nullable
    private static String entityId(@Nullable VehicleEntity hull) {
        if (hull == null) return null;
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(hull.getType());
        return key == null ? null : key.toString();
    }

    private static boolean matches(@Nullable String id, String[] list) {
        if (id == null) return false;
        for (String known : list) {
            if (known.equals(id)) return true;
        }
        return false;
    }
}
