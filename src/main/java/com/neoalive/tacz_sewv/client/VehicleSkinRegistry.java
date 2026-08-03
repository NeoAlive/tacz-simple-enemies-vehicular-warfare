package com.neoalive.tacz_sewv.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.util.CrewFacts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Scans {@code config/tacz_sewv/vehicle_skins/} for {@code <vehiclePath>_<faction>.png} and
 * registers each as a {@link DynamicTexture} under {@code tacz_sewv:dynamic/vehicle_skins/...}.
 *
 * <p>On every reload, missing files are seeded from jar defaults under
 * {@code assets/tacz_sewv/vehicle_skins_defaults/} — existing config files are never overwritten.
 */
@OnlyIn(Dist.CLIENT)
public final class VehicleSkinRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String LOG_PREFIX = "[sewv-skins]";

    /** Bundled examples shipped in the jar; copied into the config folder only if absent. */
    private static final String[] DEFAULT_SKINS = {
            "m_1a_2_ru.png",
            "m_1a_2_pmc.png",
            "t_90a_us.png",
            "t_90a_pmc.png",
    };

    /** vehicle registry path + faction → registered texture id. */
    private static final Map<String, ResourceLocation> TEXTURES = new HashMap<>();

    private VehicleSkinRegistry() {
    }

    public static Path skinsDirectory() {
        return FMLPaths.CONFIGDIR.get().resolve(TaczSewv.MODID).resolve("vehicle_skins");
    }

    /** Release prior dynamics and re-scan the folder. Safe to call repeatedly. */
    public static synchronized void reload() {
        TextureManager textures = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation id : TEXTURES.values()) {
            textures.release(id);
        }
        TEXTURES.clear();

        Path dir = skinsDirectory();
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            LOGGER.warn("{} could not create {}: {}", LOG_PREFIX, dir, e.toString());
            return;
        }

        seedDefaults(dir);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.png")) {
            for (Path file : stream) {
                tryLoad(file);
            }
        } catch (Exception e) {
            LOGGER.warn("{} could not list {}: {}", LOG_PREFIX, dir, e.toString());
        }
        LOGGER.info("{} loaded {} skin(s) from {}", LOG_PREFIX, TEXTURES.size(), dir);
    }

    @Nullable
    public static ResourceLocation get(String vehiclePath, CrewFacts.Faction faction) {
        if (vehiclePath == null || faction == null) return null;
        return TEXTURES.get(key(vehiclePath, faction));
    }

    /** Factions that have a loaded PNG for this hull registry path (any order). */
    public static java.util.List<CrewFacts.Faction> factionsFor(String vehiclePath) {
        java.util.List<CrewFacts.Faction> out = new java.util.ArrayList<>(3);
        if (vehiclePath == null) return out;
        for (CrewFacts.Faction faction : CrewFacts.Faction.values()) {
            if (TEXTURES.containsKey(key(vehiclePath, faction))) {
                out.add(faction);
            }
        }
        return out;
    }

    public static int size() {
        return TEXTURES.size();
    }

    /**
     * Copy jar-bundled example skins into the config folder. Never overwrites an existing file —
     * so iterating on art keeps edits, and a fresh install still gets working examples.
     */
    private static void seedDefaults(Path dir) {
        int copied = 0;
        for (String name : DEFAULT_SKINS) {
            Path dest = dir.resolve(name);
            if (Files.exists(dest)) continue;
            String resource = "/assets/" + TaczSewv.MODID + "/vehicle_skins_defaults/" + name;
            try (InputStream in = VehicleSkinRegistry.class.getResourceAsStream(resource)) {
                if (in == null) {
                    LOGGER.warn("{} missing jar default: {}", LOG_PREFIX, resource);
                    continue;
                }
                Files.copy(in, dest);
                copied++;
            } catch (Exception e) {
                LOGGER.warn("{} could not seed {}: {}", LOG_PREFIX, name, e.toString());
            }
        }
        if (copied > 0) {
            LOGGER.info("{} seeded {} default skin(s) into {}", LOG_PREFIX, copied, dir);
        }
    }

    private static void tryLoad(Path file) {
        String name = file.getFileName().toString();
        Parsed parsed = parseFilename(name);
        if (parsed == null) {
            LOGGER.info("{} skipped invalid file: {}", LOG_PREFIX, name);
            return;
        }

        ResourceLocation id = new ResourceLocation(TaczSewv.MODID,
                "dynamic/vehicle_skins/" + parsed.vehiclePath + "_" + parsed.factionKey + ".png");
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(in);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
            TEXTURES.put(key(parsed.vehiclePath, parsed.faction), id);
        } catch (Exception e) {
            LOGGER.info("{} skipped invalid file: {} ({})", LOG_PREFIX, name, e.toString());
        }
    }

    @Nullable
    private static Parsed parseFilename(String filename) {
        if (!filename.endsWith(".png")) return null;
        String base = filename.substring(0, filename.length() - 4);
        int under = base.lastIndexOf('_');
        if (under <= 0 || under >= base.length() - 1) return null;

        String vehiclePath = base.substring(0, under);
        String factionKey = base.substring(under + 1).toLowerCase(Locale.ROOT);
        CrewFacts.Faction faction = switch (factionKey) {
            case "ru" -> CrewFacts.Faction.RU;
            case "us" -> CrewFacts.Faction.US;
            case "pmc" -> CrewFacts.Faction.PMC;
            default -> null;
        };
        if (faction == null || vehiclePath.isEmpty()) return null;
        return new Parsed(vehiclePath, factionKey, faction);
    }

    private static String key(String vehiclePath, CrewFacts.Faction faction) {
        return vehiclePath + "_" + faction.name().toLowerCase(Locale.ROOT);
    }

    private record Parsed(String vehiclePath, String factionKey, CrewFacts.Faction faction) {
    }
}
