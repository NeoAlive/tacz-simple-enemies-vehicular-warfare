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
import java.util.TreeSet;

import javax.annotation.Nullable;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.crew.CrewFacts;

/**
 * Scans {@code config/tacz_sewv/armor_skins/} for SEM wearer-faction armor skins.
 *
 * <p>Filenames: {@code <faction>_<armorKind>.png} or {@code <faction>_<armorKind>_<N>.png}.
 * Kind is the item registry path with a leading {@code us_}/{@code ru_} stripped (so
 * {@code superbwarfare:us_chest_iotv} → {@code chest_iotv}). Numbered files are matched
 * <b>sets</b>: one shared {@code N} across currently worn pieces (intersection of available keys).
 *
 * <p>On every reload, missing files are seeded from jar defaults under
 * {@code assets/tacz_sewv/armor_skins_defaults/} — existing config files are never overwritten.
 */
@OnlyIn(Dist.CLIENT)
public final class ArmorSkinRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String LOG_PREFIX = "[sewv-armor-skins]";

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    /** Bundled skins shipped in the jar; copied into the config folder only if absent. */
    private static final String[] DEFAULT_SKINS = {
            "us_chest_iotv_1.png",
            "us_chest_iotv_2.png",
            "us_chest_iotv_3.png",
            "us_helmet_pasgt_1.png",
            "us_helmet_pasgt_2.png",
            "us_helmet_pasgt_3.png",
            "ru_chest_6b43_1.png",
            "ru_chest_6b43_2.png",
            "ru_chest_6b43_3.png",
            "ru_chest_6b43_4.png",
            "ru_helmet_6b47_1.png",
            "ru_helmet_6b47_2.png",
            "ru_helmet_6b47_3.png",
            "ru_helmet_6b47_4.png",
            "pmc_chest_iotv_1.png",
            "pmc_chest_iotv_2.png",
            "pmc_helmet_pasgt_1.png",
            "pmc_helmet_pasgt_2.png",
    };

    /** faction_kind → plain and/or numbered sets. */
    private static final Map<String, Entry> ENTRIES = new HashMap<>();
    private static final List<ResourceLocation> REGISTERED = new ArrayList<>();

    private ArmorSkinRegistry() {
    }

    public static Path skinsDirectory() {
        return FMLPaths.CONFIGDIR.get().resolve(TaczSewv.MODID).resolve("armor_skins");
    }

    /** Release prior dynamics and re-scan the folder. Safe to call repeatedly. */
    public static synchronized void reload() {
        TextureManager textures = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation id : REGISTERED) {
            textures.release(id);
        }
        REGISTERED.clear();
        ENTRIES.clear();

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
        LOGGER.info("{} loaded {} skin file(s) / {} faction+kind entries from {}",
                LOG_PREFIX, REGISTERED.size(), ENTRIES.size(), dir);
    }

    /**
     * Item registry path → armor kind key. Strips a leading {@code us_}/{@code ru_} so PMC and US
     * share {@code chest_iotv} for {@code us_chest_iotv}.
     */
    public static String armorKind(String itemPath) {
        if (itemPath == null || itemPath.isEmpty()) return itemPath;
        if (itemPath.startsWith("us_") || itemPath.startsWith("ru_")) {
            return itemPath.substring(3);
        }
        return itemPath;
    }

    /**
     * Exact set lookup, then plain. {@code setN < 0} skips numbered and uses plain only.
     * Missing art → null (leave the stock SBW texture).
     */
    @Nullable
    public static ResourceLocation get(String kind, CrewFacts.Faction faction, int setN) {
        if (kind == null || faction == null) return null;
        Entry entry = ENTRIES.get(key(faction, kind));
        if (entry == null) return null;
        if (setN >= 0) {
            ResourceLocation numbered = entry.numbered.get(setN);
            if (numbered != null) return numbered;
        }
        return entry.plain;
    }

    /** Numbered set ids present for this kind+faction (empty if none / plain-only). */
    public static TreeSet<Integer> variantKeys(String kind, CrewFacts.Faction faction) {
        TreeSet<Integer> out = new TreeSet<>();
        if (kind == null || faction == null) return out;
        Entry entry = ENTRIES.get(key(faction, kind));
        if (entry != null) {
            out.addAll(entry.numbered.keySet());
        }
        return out;
    }

    /**
     * Shared set id for everything this SEM unit is wearing, or {@code -1} if no matched set
     * applies (plain-only kit / no skins). Intersection of numbered keys across worn pieces that
     * have any {@code _N} files; UUID picks one key from that intersection.
     */
    public static int resolveSetN(LivingEntity wearer, CrewFacts.Faction faction) {
        TreeSet<Integer> intersection = null;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = wearer.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (itemId == null) continue;
            String kind = armorKind(itemId.getPath());
            TreeSet<Integer> keys = variantKeys(kind, faction);
            if (keys.isEmpty()) continue; // plain-only — does not constrain
            if (intersection == null) {
                intersection = new TreeSet<>(keys);
            } else {
                intersection.retainAll(keys);
            }
        }
        if (intersection == null || intersection.isEmpty()) return -1;
        List<Integer> sorted = new ArrayList<>(intersection);
        int index = Math.floorMod(wearer.getUUID().hashCode(), sorted.size());
        return sorted.get(index);
    }

    /**
     * Resolve the override texture for one worn stack, or null for stock.
     */
    @Nullable
    public static ResourceLocation textureFor(LivingEntity wearer, ItemStack stack, CrewFacts.Faction faction) {
        if (stack == null || stack.isEmpty() || faction == null) return null;
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) return null;
        String kind = armorKind(itemId.getPath());
        int setN = resolveSetN(wearer, faction);
        return get(kind, faction, setN);
    }

    public static int size() {
        return REGISTERED.size();
    }

    private static void seedDefaults(Path dir) {
        int copied = 0;
        for (String name : DEFAULT_SKINS) {
            Path dest = dir.resolve(name);
            if (Files.exists(dest)) continue;
            String resource = "/assets/" + TaczSewv.MODID + "/armor_skins_defaults/" + name;
            try (InputStream in = ArmorSkinRegistry.class.getResourceAsStream(resource)) {
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

        String dynPath = parsed.setN < 0
                ? "dynamic/armor_skins/" + parsed.factionKey + "_" + parsed.kind + ".png"
                : "dynamic/armor_skins/" + parsed.factionKey + "_" + parsed.kind + "_" + parsed.setN + ".png";
        ResourceLocation id = new ResourceLocation(TaczSewv.MODID, dynPath);
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(in);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
            REGISTERED.add(id);
            Entry entry = ENTRIES.computeIfAbsent(key(parsed.faction, parsed.kind), k -> new Entry());
            if (parsed.setN < 0) {
                if (entry.plain != null) {
                    LOGGER.info("{} duplicate plain skin, keeping first: {}", LOG_PREFIX, name);
                } else {
                    entry.plain = id;
                }
            } else if (entry.numbered.put(parsed.setN, id) != null) {
                LOGGER.info("{} duplicate set skin, overwrote: {}", LOG_PREFIX, name);
            }
        } catch (Exception e) {
            LOGGER.info("{} skipped invalid file: {} ({})", LOG_PREFIX, name, e.toString());
        }
    }

    /**
     * {@code faction_kind.png} or {@code faction_kind_N.png}. Trailing all-digit segment is the
     * optional set id; the first segment must be ru/us/pmc.
     */
    @Nullable
    private static Parsed parseFilename(String filename) {
        if (!filename.endsWith(".png")) return null;
        String base = filename.substring(0, filename.length() - 4);
        int under = base.lastIndexOf('_');
        if (under <= 0 || under >= base.length() - 1) return null;

        String last = base.substring(under + 1).toLowerCase(Locale.ROOT);
        int setN = -1;
        String rest = base;
        if (isAllDigits(last)) {
            try {
                setN = Integer.parseInt(last);
            } catch (NumberFormatException e) {
                return null;
            }
            if (setN < 0) return null;
            rest = base.substring(0, under);
        }

        int first = rest.indexOf('_');
        if (first <= 0 || first >= rest.length() - 1) return null;
        String factionKey = rest.substring(0, first).toLowerCase(Locale.ROOT);
        String kind = rest.substring(first + 1);
        CrewFacts.Faction faction = parseFaction(factionKey);
        if (faction == null || kind.isEmpty()) return null;
        return new Parsed(factionKey, faction, kind, setN);
    }

    @Nullable
    private static CrewFacts.Faction parseFaction(String key) {
        return switch (key) {
            case "ru" -> CrewFacts.Faction.RU;
            case "us" -> CrewFacts.Faction.US;
            case "pmc" -> CrewFacts.Faction.PMC;
            default -> null;
        };
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static String key(CrewFacts.Faction faction, String kind) {
        return faction.name().toLowerCase(Locale.ROOT) + "_" + kind;
    }

    private static final class Entry {
        @Nullable ResourceLocation plain;
        final TreeMap<Integer, ResourceLocation> numbered = new TreeMap<>();
    }

    private record Parsed(String factionKey, CrewFacts.Faction faction, String kind, int setN) {
    }
}
