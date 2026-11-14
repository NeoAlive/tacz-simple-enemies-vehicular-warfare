package com.neoalive.tacz_sewv.compat.fcp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.slf4j.Logger;

/**
 * Soft-compat facade for Frontline Combat Pack 2.0+. Imports no {@code frontline.*} type: every
 * FCP call is {@code supported() && FcpHulls.x(...)}, so {@link FcpHulls} only resolves once FCP is
 * known to be there and new enough.
 */
public final class FcpCompat {

    public static final String MODID = "fcp";
    public static final ArtifactVersion MIN_VERSION = new DefaultArtifactVersion("2.0.0");

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<EntityType<?>, Boolean> FCP_TYPES = new ConcurrentHashMap<>();

    private static Boolean present;
    private static Boolean supported;

    private FcpCompat() {}

    public static boolean present() {
        Boolean p = present;
        if (p == null) {
            p = ModList.get() != null && ModList.get().isLoaded(MODID);
            present = p;
        }
        return p;
    }

    /** Installed and 2.0.0 or newer. Older builds are refused by mods.toml; this is the backstop. */
    public static boolean supported() {
        Boolean s = supported;
        if (s == null) {
            s = present() && versionOk();
            supported = s;
        }
        return s;
    }

    private static boolean versionOk() {
        try {
            IModInfo info = ModList.get().getModContainerById(MODID).map(c -> c.getModInfo()).orElse(null);
            return info != null && isSupportedVersion(info.getVersion());
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isSupportedVersion(@Nullable ArtifactVersion version) {
        return version != null && version.compareTo(MIN_VERSION) >= 0;
    }

    public static void reportAvailability() {
        if (!present()) return;
        if (supported()) {
            LOGGER.info("Frontline Combat Pack soft-compat active");
            LOGGER.warn("[sewv] Frontline Combat Pack owns SBW's vehicle damage hook - TaCZ ballistic "
                    + "translation and mob melee damage against vehicles are inactive on this install.");
        } else {
            LOGGER.error("[sewv] Frontline Combat Pack older than {} is not supported; FCP compat is off.",
                    MIN_VERSION);
        }
    }

    /** Registered under the fcp namespace. Cached per type. */
    public static boolean isFcp(@Nullable VehicleEntity hull) {
        if (hull == null || !supported()) return false;
        return FCP_TYPES.computeIfAbsent(hull.getType(), t -> {
            ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(t);
            return key != null && MODID.equals(key.getNamespace());
        });
    }

    /** The hull keeps ammo in FCP's own hold instead of SBW's container. */
    public static boolean hasHold(@Nullable VehicleEntity hull) {
        return isFcp(hull) && FcpHulls.hasHold(hull);
    }

    /** True while FCP's engine gate is blocking the hull's driving input. */
    public static boolean engineBlocked(@Nullable VehicleEntity hull) {
        return isFcp(hull) && FcpHulls.engineBlocked(hull);
    }

    /** Skip the start-up: for hulls spawned already crewed. */
    public static void startEngineNow(@Nullable VehicleEntity hull) {
        if (isFcp(hull)) FcpHulls.startEngineNow(hull);
    }

    /** One tick of a held start key. Has to be repeated every tick until the engine runs. */
    public static void holdEngineStart(@Nullable VehicleEntity hull) {
        if (isFcp(hull)) FcpHulls.holdEngineStart(hull);
    }

    /** FCP's own combat load: the hull's loadout table, decoys and a full tank. */
    public static void fillCombatLoad(@Nullable VehicleEntity hull) {
        if (isFcp(hull)) FcpHulls.fillCombatLoad(hull);
    }

    /** FCP tunes these as artillery (chunk loading, gravity multiplier). */
    public static boolean isArtillery(@Nullable VehicleEntity hull) {
        return isFcp(hull) && FcpHulls.isArtillery(hull);
    }
}
