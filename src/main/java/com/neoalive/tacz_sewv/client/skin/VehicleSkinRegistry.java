package com.neoalive.tacz_sewv.client.skin;

import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.crew.CrewFacts;

/**
 * Scans {@code config/tacz_sewv/vehicle_skins/} for faction skins and registers each as a
 * {@link DynamicTexture} under {@code tacz_sewv:dynamic/vehicle_skins/...}.
 *
 * <p>Filenames (optional trailing index enables an RNG pool for that hull+faction):
 * <ul>
 *   <li>{@code <vehiclePath>_<faction>.png} — single skin, no RNG</li>
 *   <li>{@code <vehiclePath>_<faction>_<N>.png} — pool member ({@code N} = 0, 1, 2, …)</li>
 * </ul>
 * If any numbered files exist for a pair, lookup uses the sticky salt against that pool and
 * ignores a plain sibling. No numbered files → the plain file (if any).
 *
 * <p>On every reload, missing files are seeded from jar defaults under
 * {@code assets/tacz_sewv/vehicle_skins_defaults/} — existing config files are never overwritten.
 * The default set is enumerated out of the jar by {@link SkinFiles}; the hardcoded list this used
 * to carry named 4 of the 20 skins actually shipped, so 16 of them never reached disk.
 */
@OnlyIn(Dist.CLIENT)
public final class VehicleSkinRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String LOG_PREFIX = "[sewv-skins]";

    private static final String DEFAULTS_ROOT = "vehicle_skins_defaults";

    /** vehicle registry path + faction → plain and/or numbered pool. */
    private static final Map<String, Entry> ENTRIES = new HashMap<>();
    /** Every DynamicTexture id we registered (plain + variants) for release on reload. */
    private static final List<ResourceLocation> REGISTERED = new ArrayList<>();
    /** skin id → darkened DynamicTexture for wreck rendering (never goes through ResourceManager). */
    private static final Map<ResourceLocation, ResourceLocation> DARKENED = new HashMap<>();

    private VehicleSkinRegistry() {
    }

    public static Path skinsDirectory() {
        return FMLPaths.CONFIGDIR.get().resolve(TaczSewv.MODID).resolve("vehicle_skins");
    }

    /** Release prior dynamics and re-scan the folder. Safe to call repeatedly. */
    public static synchronized void reload(ResourceManager resources) {
        TextureManager textures = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation id : REGISTERED) {
            textures.release(id);
        }
        for (ResourceLocation id : DARKENED.values()) {
            textures.release(id);
        }
        REGISTERED.clear();
        ENTRIES.clear();
        DARKENED.clear();

        Path dir = skinsDirectory();
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            LOGGER.warn("{} could not create {}: {}", LOG_PREFIX, dir, e.toString());
            return;
        }

        SkinFiles.seed(dir, DEFAULTS_ROOT, resources, LOG_PREFIX);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.png")) {
            for (Path file : stream) {
                tryLoad(file);
            }
        } catch (Exception e) {
            LOGGER.warn("{} could not list {}: {}", LOG_PREFIX, dir, e.toString());
        }
        LOGGER.info("{} loaded {} skin file(s) / {} hull+faction entries from {}",
                LOG_PREFIX, REGISTERED.size(), ENTRIES.size(), dir);
    }

    /**
     * Resolve a texture for sticky paint. Numbered pool (if any) wins: {@code salt % poolSize}.
     * Otherwise the plain {@code path_faction.png}. Missing art → null (stock texture).
     */
    @Nullable
    public static ResourceLocation get(String vehiclePath, CrewFacts.Faction faction, int salt) {
        if (vehiclePath == null || faction == null) return null;
        Entry entry = ENTRIES.get(key(vehiclePath, faction));
        if (entry == null) return null;
        if (!entry.variants.isEmpty()) {
            int index = Math.floorMod(salt, entry.variants.size());
            return entry.variants.get(index);
        }
        return entry.plain;
    }

    /** Factions that have a loaded PNG (plain or any numbered) for this hull registry path. */
    public static List<CrewFacts.Faction> factionsFor(String vehiclePath) {
        List<CrewFacts.Faction> out = new ArrayList<>(3);
        if (vehiclePath == null) return out;
        for (CrewFacts.Faction faction : CrewFacts.Faction.values()) {
            Entry entry = ENTRIES.get(key(vehiclePath, faction));
            if (entry != null && entry.hasAny()) {
                out.add(faction);
            }
        }
        return out;
    }

    public static int size() {
        return REGISTERED.size();
    }

    /**
     * Wreck darkening for TextureManager-only skins. SBW's {@code TextureBrightnessHandler}
     * cannot see these (ResourceManager only), so we multiply pixels here and register a sibling
     * DynamicTexture. Falls back to {@code skin} on any failure — never throws.
     */
    public static ResourceLocation darkened(ResourceLocation skin, float multiplier) {
        ResourceLocation cached = DARKENED.get(skin);
        if (cached != null) return cached;

        try {
            AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(skin);
            if (!(tex instanceof DynamicTexture dynamic)) return skin;
            NativeImage src = dynamic.getPixels();
            if (src == null) return skin;

            NativeImage dark = new NativeImage(src.getWidth(), src.getHeight(), false);
            for (int x = 0; x < src.getWidth(); x++) {
                for (int y = 0; y < src.getHeight(); y++) {
                    int color = src.getPixelRGBA(x, y);
                    int a = (color >> 24) & 0xFF;
                    int r = Math.min(255, (int) (((color >> 16) & 0xFF) * multiplier));
                    int g = Math.min(255, (int) (((color >> 8) & 0xFF) * multiplier));
                    int b = Math.min(255, (int) ((color & 0xFF) * multiplier));
                    dark.setPixelRGBA(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }

            String path = skin.getPath();
            ResourceLocation darkId = new ResourceLocation(skin.getNamespace(),
                    path.endsWith(".png") ? path.substring(0, path.length() - 4) + "_dark.png" : path + "_dark");
            Minecraft.getInstance().getTextureManager().register(darkId, new DynamicTexture(dark));
            DARKENED.put(skin, darkId);
            return darkId;
        } catch (Exception e) {
            LOGGER.warn("{} could not darken {}: {}", LOG_PREFIX, skin, e.toString());
            return skin;
        }
    }

    /**
     * Copy jar-bundled example skins into the config folder. Never overwrites an existing file —
     * so iterating on art keeps edits, and a fresh install still gets working examples.
     */
    /** Throw away whatever is on disk and put the jar's own art back. */
    public static synchronized void resetToDefaults(ResourceManager resources) {
        SkinFiles.wipe(skinsDirectory(), LOG_PREFIX);
        reload(resources);
    }

    private static void tryLoad(Path file) {
        String name = file.getFileName().toString();
        Parsed parsed = parseFilename(name);
        if (parsed == null) {
            LOGGER.info("{} skipped invalid file: {}", LOG_PREFIX, name);
            return;
        }

        String dynPath = parsed.variant < 0
                ? "dynamic/vehicle_skins/" + parsed.vehiclePath + "_" + parsed.factionKey + ".png"
                : "dynamic/vehicle_skins/" + parsed.vehiclePath + "_" + parsed.factionKey
                        + "_" + parsed.variant + ".png";
        ResourceLocation id = new ResourceLocation(TaczSewv.MODID, dynPath);
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(in);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
            REGISTERED.add(id);
            Entry entry = ENTRIES.computeIfAbsent(key(parsed.vehiclePath, parsed.faction), k -> new Entry());
            if (parsed.variant < 0) {
                if (entry.plain != null) {
                    LOGGER.info("{} duplicate plain skin, keeping first: {}", LOG_PREFIX, name);
                } else {
                    entry.plain = id;
                }
            } else {
                entry.numbered.put(parsed.variant, id);
                entry.rebuildVariants();
            }
        } catch (Exception e) {
            LOGGER.info("{} skipped invalid file: {} ({})", LOG_PREFIX, name, e.toString());
        }
    }

    /**
     * {@code path_faction.png} or {@code path_faction_N.png}. Trailing all-digit segment is the
     * optional pool index; the segment before that must be ru/us/pmc.
     */
    @Nullable
    private static Parsed parseFilename(String filename) {
        if (!filename.endsWith(".png")) return null;
        String base = filename.substring(0, filename.length() - 4);
        int under = base.lastIndexOf('_');
        if (under <= 0 || under >= base.length() - 1) return null;

        String last = base.substring(under + 1).toLowerCase(Locale.ROOT);
        int variant = -1;
        String rest = base.substring(0, under);
        if (isAllDigits(last)) {
            try {
                variant = Integer.parseInt(last);
            } catch (NumberFormatException e) {
                return null;
            }
            if (variant < 0) return null;
            under = rest.lastIndexOf('_');
            if (under <= 0 || under >= rest.length() - 1) return null;
            last = rest.substring(under + 1).toLowerCase(Locale.ROOT);
            rest = rest.substring(0, under);
        }

        CrewFacts.Faction faction = switch (last) {
            case "ru" -> CrewFacts.Faction.RU;
            case "us" -> CrewFacts.Faction.US;
            case "pmc" -> CrewFacts.Faction.PMC;
            default -> null;
        };
        if (faction == null || rest.isEmpty()) return null;
        return new Parsed(rest, last, faction, variant);
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static String key(String vehiclePath, CrewFacts.Faction faction) {
        return vehiclePath + "_" + faction.name().toLowerCase(Locale.ROOT);
    }

    private static final class Entry {
        @Nullable ResourceLocation plain;
        /** Sorted by index so salt % size is stable across reloads. */
        final TreeMap<Integer, ResourceLocation> numbered = new TreeMap<>();
        /** Flattened in index order; rebuilt lazily after load (variants filled at end of each put). */
        final List<ResourceLocation> variants = new ArrayList<>();

        boolean hasAny() {
            return plain != null || !numbered.isEmpty();
        }

        void rebuildVariants() {
            variants.clear();
            variants.addAll(numbered.values());
        }
    }

    private record Parsed(String vehiclePath, String factionKey, CrewFacts.Faction faction, int variant) {
    }
}
