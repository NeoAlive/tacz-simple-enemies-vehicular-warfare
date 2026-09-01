package com.neoalive.tacz_sewv.client.skin;

import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.entity.ai.support.SupportRole;
import com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity;
import com.neoalive.tacz_sewv.entity.unit.RuCombatEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.RuEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.RuMedicEntity;
import com.neoalive.tacz_sewv.entity.unit.UsCombatEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.UsEngineerEntity;
import com.neoalive.tacz_sewv.entity.unit.UsMedicEntity;

/**
 * Faction paint for SEM crews: armor piece textures, camo-synced uniforms, SEM variant overrides,
 * and support-role defaults — one registry so armor and body never disagree.
 *
 * <p>Config folders (client-side, reload with F3+T or {@code /sewv debug reloadSkins}):
 * <ul>
 *   <li>{@code armor_skins/} — {@code <faction>_<piece>[_camo[_rng]].png}</li>
 *   <li>{@code unit_skins/} — SEM-native layout: {@code ru_unit/ru_unit_default.png}, role folders
 *       ({@code ru_medic/ru_medic_default.png}), optional {@code camo/} for armor-matched uniforms</li>
 *   <li>{@code skin_pools/} — legacy alias; same camo filenames, deprecated</li>
 * </ul>
 *
 * <p>Body resolution order in {@link #bodySkin}: camo-synced pool → SEM variant index → role
 * default → null (SEM jar fallback).
 */
@OnlyIn(Dist.CLIENT)
public final class CrewSkinRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String LOG_PREFIX = "[sewv-crew-skins]";

    private static final String ARMOR_DEFAULTS = "armor_skins_defaults";
    private static final String SKIN_POOL_DEFAULTS = "skin_pools_defaults";
    private static final String UNIT_SKIN_DEFAULTS = "unit_skins_defaults";

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    /** Body-skin categories. Kept as constants because {@link #category} is the only writer. */
    private static final String INFANTRY = "infantry";
    private static final String MEDIC = "medic";
    private static final String COMBAT_ENGINEER = "combat_engineer";
    private static final String MECHANICAL_ENGINEER = "mechanical_engineer";
    private static final String COMMANDER = "commander";

    /** faction_kind → plain and/or camo pools. */
    private static final Map<String, Entry> ENTRIES = new HashMap<>();
    /** ru_unit / us_unit / pmc_unit → sorted variant textures (SEM index order). */
    private static final Map<String, ResourceLocation[]> VARIANT_SKINS = new HashMap<>();
    /** ru_medic / pmc_commander / … → plain role skin. */
    private static final Map<String, ResourceLocation> ROLE_DEFAULTS = new HashMap<>();
    /** pmc_commander → beret tint mask. */
    private static final Map<String, ResourceLocation> ROLE_OVERLAYS = new HashMap<>();
    private static final List<ResourceLocation> REGISTERED = new ArrayList<>();

    private static final Set<String> ROLE_FOLDERS = Set.of(
            "ru_medic", "us_medic", "ru_engineer", "us_engineer",
            "ru_combat_engineer", "us_combat_engineer", "pmc_commander");

    private CrewSkinRegistry() {
    }

    public static Path armorDirectory() {
        return FMLPaths.CONFIGDIR.get().resolve(TaczSewv.MODID).resolve("armor_skins");
    }

    /** Legacy camo-pool folder; still scanned for backwards compatibility. */
    public static Path skinPoolDirectory() {
        return FMLPaths.CONFIGDIR.get().resolve(TaczSewv.MODID).resolve("skin_pools");
    }

    public static Path unitSkinDirectory() {
        return FMLPaths.CONFIGDIR.get().resolve(TaczSewv.MODID).resolve("unit_skins");
    }

    /** Release prior dynamics and re-scan all folders. Safe to call repeatedly. */
    public static synchronized void reload(ResourceManager resources) {
        TextureManager textures = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation id : REGISTERED) {
            textures.release(id);
        }
        REGISTERED.clear();
        ENTRIES.clear();
        VARIANT_SKINS.clear();
        ROLE_DEFAULTS.clear();
        ROLE_OVERLAYS.clear();

        load(armorDirectory(), ARMOR_DEFAULTS, resources, false);
        loadUnitSkins(unitSkinDirectory(), resources);
        if (Files.isDirectory(skinPoolDirectory())) {
            LOGGER.info("{} skin_pools/ is deprecated; use unit_skins/ (still loaded for compatibility)",
                    LOG_PREFIX);
            load(skinPoolDirectory(), SKIN_POOL_DEFAULTS, resources, true);
        }

        LOGGER.info("{} loaded {} skin file(s) / {} camo entries / {} variant sets / {} role defaults",
                LOG_PREFIX, REGISTERED.size(), ENTRIES.size(), VARIANT_SKINS.size(), ROLE_DEFAULTS.size());
    }

    /**
     * Throw away whatever is on disk and put the jar's own art back. Deleting first is what makes
     * this a real reset rather than a top-up.
     */
    public static synchronized void resetToDefaults(ResourceManager resources) {
        SkinFiles.wipe(armorDirectory(), LOG_PREFIX);
        SkinFiles.wipe(unitSkinDirectory(), LOG_PREFIX);
        SkinFiles.wipe(skinPoolDirectory(), LOG_PREFIX);
        reload(resources);
    }

    private static void load(Path dir, String defaultsRoot, ResourceManager resources, boolean recursive) {
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            LOGGER.warn("{} could not create {}: {}", LOG_PREFIX, dir, e.toString());
            return;
        }
        SkinFiles.seed(dir, defaultsRoot, resources, LOG_PREFIX);

        if (recursive) {
            try (Stream<Path> tree = Files.walk(dir)) {
                tree.filter(p -> p.getFileName().toString().endsWith(".png")).forEach(CrewSkinRegistry::tryLoad);
            } catch (Exception e) {
                LOGGER.warn("{} could not walk {}: {}", LOG_PREFIX, dir, e.toString());
            }
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.png")) {
            for (Path file : stream) {
                tryLoad(file);
            }
        } catch (Exception e) {
            LOGGER.warn("{} could not list {}: {}", LOG_PREFIX, dir, e.toString());
        }
    }

    private static void loadUnitSkins(Path dir, ResourceManager resources) {
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            LOGGER.warn("{} could not create {}: {}", LOG_PREFIX, dir, e.toString());
            return;
        }
        SkinFiles.seed(dir, UNIT_SKIN_DEFAULTS, resources, LOG_PREFIX);

        if (!Files.isDirectory(dir)) return;

        try (Stream<Path> children = Files.list(dir)) {
            for (Path sub : children.filter(Files::isDirectory).toList()) {
                String name = sub.getFileName().toString();
                if (name.matches("(ru|us|pmc)_unit")) {
                    loadVariantFolder(name, sub);
                } else if (ROLE_FOLDERS.contains(name)) {
                    loadRoleFolder(name, sub);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("{} could not list unit skin subfolders: {}", LOG_PREFIX, e.toString());
        }

        try (Stream<Path> tree = Files.walk(dir)) {
            tree.filter(p -> p.getFileName().toString().endsWith(".png"))
                    .filter(CrewSkinRegistry::shouldLoadAsCamo)
                    .forEach(CrewSkinRegistry::tryLoad);
        } catch (Exception e) {
            LOGGER.warn("{} could not walk {}: {}", LOG_PREFIX, dir, e.toString());
        }
    }

    private static void loadVariantFolder(String folderKey, Path dir) {
        List<Path> files = listSortedPngs(dir);
        if (files.isEmpty()) return;
        List<ResourceLocation> ids = new ArrayList<>(files.size());
        for (Path file : files) {
            String dynPath = "dynamic/unit_skins/" + folderKey + "/" + file.getFileName().toString();
            ResourceLocation id = registerDynamic(file, dynPath);
            if (id != null) ids.add(id);
        }
        if (!ids.isEmpty()) {
            VARIANT_SKINS.put(folderKey, ids.toArray(new ResourceLocation[0]));
        }
    }

    private static void loadRoleFolder(String folderKey, Path dir) {
        List<Path> files = listSortedPngs(dir);
        for (Path file : files) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            String dynPath = "dynamic/unit_skins/" + folderKey + "/" + file.getFileName().toString();
            ResourceLocation id = registerDynamic(file, dynPath);
            if (id == null) continue;
            if (name.contains("_overlay.")) {
                ROLE_OVERLAYS.put(folderKey, id);
            } else if (name.contains("_default.") || !ROLE_DEFAULTS.containsKey(folderKey)) {
                ROLE_DEFAULTS.put(folderKey, id);
            }
        }
    }

    private static List<Path> listSortedPngs(Path dir) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".png")).forEach(files::add);
        } catch (Exception e) {
            LOGGER.warn("{} could not list {}: {}", LOG_PREFIX, dir, e.toString());
            return files;
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return files;
    }

    /** Camo-named body skins only — variant/role folder PNGs use SEM filenames that must not enter ENTRIES. */
    private static boolean shouldLoadAsCamo(Path file) {
        Path parent = file.getParent();
        if (parent == null) return false;
        String parentName = parent.getFileName().toString();
        if (parentName.matches("(ru|us|pmc)_unit") || ROLE_FOLDERS.contains(parentName)) {
            return false;
        }
        Parsed parsed = parseFilename(file.getFileName().toString());
        return parsed != null && isBodyCategory(parsed.kind());
    }

    private static boolean isBodyCategory(String kind) {
        return INFANTRY.equals(kind) || MEDIC.equals(kind) || COMBAT_ENGINEER.equals(kind)
                || MECHANICAL_ENGINEER.equals(kind) || COMMANDER.equals(kind);
    }

    @Nullable
    private static ResourceLocation registerDynamic(Path file, String dynPath) {
        ResourceLocation id = new ResourceLocation(TaczSewv.MODID, dynPath.toLowerCase(Locale.ROOT));
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(in);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
            REGISTERED.add(id);
            return id;
        } catch (Exception e) {
            LOGGER.debug("{} skipped invalid file: {} ({})", LOG_PREFIX, file.getFileName(), e.toString());
            return null;
        }
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
     * Exact camo lookup, then plain. {@code camo < 0} skips the pools and uses plain only.
     * {@code rngKey} picks among that camo's variants. Missing art → null (leave the stock texture).
     */
    @Nullable
    public static ResourceLocation get(String kind, CrewFacts.Faction faction, int camo, int rngKey) {
        if (kind == null || faction == null) return null;
        Entry entry = ENTRIES.get(key(faction, kind));
        if (entry == null) return null;
        if (camo >= 0) {
            TreeMap<Integer, ResourceLocation> variants = entry.pools.get(camo);
            if (variants != null && !variants.isEmpty()) {
                int index = Math.floorMod(rngKey, variants.size());
                for (ResourceLocation id : variants.values()) {
                    if (index-- == 0) return id;
                }
            }
        }
        return entry.plain;
    }

    /** Camo ids present for this kind+faction (empty if none / plain-only). */
    public static TreeSet<Integer> variantKeys(String kind, CrewFacts.Faction faction) {
        TreeSet<Integer> out = new TreeSet<>();
        if (kind == null || faction == null) return out;
        Entry entry = ENTRIES.get(key(faction, kind));
        if (entry != null) {
            out.addAll(entry.pools.keySet());
        }
        return out;
    }

    /**
     * Shared camo id for everything this SEM unit is wearing, or {@code -1} if no matched camo
     * applies (plain-only kit / no skins).
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
            if (keys.isEmpty()) continue;
            if (intersection == null) {
                intersection = new TreeSet<>(keys);
            } else {
                intersection.retainAll(keys);
            }
        }
        return pick(intersection, wearer);
    }

    @Nullable
    public static ResourceLocation textureFor(LivingEntity wearer, ItemStack stack, CrewFacts.Faction faction) {
        if (stack == null || stack.isEmpty() || faction == null) return null;
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) return null;
        String kind = armorKind(itemId.getPath());
        return get(kind, faction, resolveSetN(wearer, faction), wearer.getUUID().hashCode());
    }

    /**
     * The unit's uniform, or null to let SEM pick its own variant.
     *
     * <p>Priority: camo-synced pool → SEM variant override → role default → null (SEM jar).
     */
    @Nullable
    public static ResourceLocation bodySkin(LivingEntity unit) {
        CrewFacts.Faction faction = CrewFacts.factionOfCrew(unit);
        if (faction == null) return null;
        String category = category(unit, faction);
        int camo = resolveSetN(unit, faction);
        if (camo < 0) {
            camo = pick(variantKeys(category, faction), unit);
        }
        ResourceLocation camoSkin = get(category, faction, camo, unit.getUUID().hashCode());
        if (camoSkin != null) return camoSkin;

        ResourceLocation variant = variantFor(unit, faction, category);
        if (variant != null) return variant;

        return roleDefault(category, faction);
    }

    /** Beret tint mask for a role folder (e.g. {@code pmc_commander}), or null. */
    @Nullable
    public static ResourceLocation overlayFor(String roleFolderKey) {
        return ROLE_OVERLAYS.get(roleFolderKey);
    }

    /** Expected variant count per SEM faction folder (for self-check). */
    public static int expectedVariantCount(String folderKey) {
        return switch (folderKey) {
            case "ru_unit" -> 5;
            case "us_unit" -> 3;
            case "pmc_unit" -> 6;
            default -> -1;
        };
    }

    /** Read-only view of loaded variant arrays (self-check). */
    public static Map<String, ResourceLocation[]> variantSkinsSnapshot() {
        return Map.copyOf(VARIANT_SKINS);
    }

    @Nullable
    private static ResourceLocation variantFor(LivingEntity unit, CrewFacts.Faction faction, String category) {
        if (!INFANTRY.equals(category)) return null;
        String folderKey = faction.name().toLowerCase(Locale.ROOT) + "_unit";
        ResourceLocation[] variants = VARIANT_SKINS.get(folderKey);
        if (variants == null || variants.length == 0) return null;
        int index = unitVariant(unit);
        if (index < 0 || index >= variants.length) return variants[0];
        return variants[index];
    }

    private static int unitVariant(LivingEntity unit) {
        if (unit instanceof RUunitEntity ru) return ru.getVariant();
        if (unit instanceof USunitEntity us) return us.getVariant();
        if (unit instanceof PmcUnitEntity pmc) return pmc.getVariant();
        return 0;
    }

    @Nullable
    private static ResourceLocation roleDefault(String category, CrewFacts.Faction faction) {
        String folder = roleFolderKey(category, faction);
        if (folder == null) return null;
        return ROLE_DEFAULTS.get(folder);
    }

    @Nullable
    static String roleFolderKey(String category, CrewFacts.Faction faction) {
        if (INFANTRY.equals(category)) return null;
        if (COMMANDER.equals(category)) return "pmc_commander";
        String prefix = faction.name().toLowerCase(Locale.ROOT);
        return switch (category) {
            case MEDIC -> prefix + "_medic";
            case COMBAT_ENGINEER -> prefix + "_combat_engineer";
            case MECHANICAL_ENGINEER -> prefix + "_engineer";
            default -> null;
        };
    }

    private static String category(LivingEntity unit, CrewFacts.Faction faction) {
        if (faction == CrewFacts.Faction.PMC) {
            if (unit instanceof PmcCommanderEntity) return COMMANDER;
            return switch (SupportRole.of(unit)) {
                case MEDIC -> MEDIC;
                case COMBAT_ENGINEER -> COMBAT_ENGINEER;
                case ENGINEER -> MECHANICAL_ENGINEER;
                case NONE -> INFANTRY;
            };
        }
        if (unit instanceof RuMedicEntity || unit instanceof UsMedicEntity) return MEDIC;
        if (unit instanceof RuCombatEngineerEntity || unit instanceof UsCombatEngineerEntity) {
            return COMBAT_ENGINEER;
        }
        if (unit instanceof RuEngineerEntity || unit instanceof UsEngineerEntity) {
            return MECHANICAL_ENGINEER;
        }
        return INFANTRY;
    }

    private static int pick(@Nullable TreeSet<Integer> camos, LivingEntity unit) {
        if (camos == null || camos.isEmpty()) return -1;
        int index = Math.floorMod(unit.getUUID().hashCode(), camos.size());
        for (int camo : camos) {
            if (index-- == 0) return camo;
        }
        return -1;
    }

    private static void tryLoad(Path file) {
        String name = file.getFileName().toString();
        Parsed parsed = parseFilename(name);
        if (parsed == null) {
            LOGGER.debug("{} skipped invalid camo file: {}", LOG_PREFIX, name);
            return;
        }

        String dynPath = ("dynamic/crew_skins/" + parsed.factionKey + "_" + parsed.kind
                + (parsed.camo < 0 ? "" : "_" + parsed.camo + "_" + parsed.rng) + ".png")
                .toLowerCase(Locale.ROOT);
        ResourceLocation id = registerDynamic(file, dynPath);
        if (id == null) return;

        Entry entry = ENTRIES.computeIfAbsent(key(parsed.faction, parsed.kind), k -> new Entry());
        if (parsed.camo < 0) {
            if (entry.plain != null) {
                LOGGER.info("{} duplicate plain skin, keeping first: {}", LOG_PREFIX, name);
            } else {
                entry.plain = id;
            }
        } else if (entry.pools.computeIfAbsent(parsed.camo, k -> new TreeMap<>())
                .put(parsed.rng, id) != null) {
            LOGGER.info("{} duplicate camo/variant skin, overwrote: {}", LOG_PREFIX, name);
        }
    }

    /**
     * {@code faction_kind[_camo[_rng]].png}. Up to two trailing all-digit segments are stripped:
     * two are camo then rng, one is a camo with {@code rng = 1} (the pre-camo naming), none is a
     * plain skin. The first segment must be ru/us/pmc.
     */
    @Nullable
    static Parsed parseFilename(String filename) {
        if (!filename.endsWith(".png")) return null;
        String rest = filename.substring(0, filename.length() - 4);

        int camo = -1;
        int rng = 1;
        int trailing = trailingNumber(rest);
        if (trailing >= 0) {
            String head = rest.substring(0, rest.lastIndexOf('_'));
            int second = trailingNumber(head);
            if (second >= 0) {
                camo = second;
                rng = trailing;
                rest = head.substring(0, head.lastIndexOf('_'));
            } else {
                camo = trailing;
                rest = head;
            }
        }

        int first = rest.indexOf('_');
        if (first <= 0 || first >= rest.length() - 1) return null;
        String factionKey = rest.substring(0, first).toLowerCase(Locale.ROOT);
        String kind = rest.substring(first + 1).toLowerCase(Locale.ROOT);
        CrewFacts.Faction faction = parseFaction(factionKey);
        if (faction == null || kind.isEmpty()) return null;
        return new Parsed(factionKey, faction, kind, camo, rng);
    }

    private static int trailingNumber(String base) {
        int under = base.lastIndexOf('_');
        if (under <= 0 || under >= base.length() - 1) return -1;
        String last = base.substring(under + 1);
        for (int i = 0; i < last.length(); i++) {
            if (!Character.isDigit(last.charAt(i))) return -1;
        }
        try {
            return Integer.parseInt(last);
        } catch (NumberFormatException e) {
            return -1;
        }
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

    private static String key(CrewFacts.Faction faction, String kind) {
        return faction.name().toLowerCase(Locale.ROOT) + "_" + kind;
    }

    private static final class Entry {
        @Nullable ResourceLocation plain;
        final TreeMap<Integer, TreeMap<Integer, ResourceLocation>> pools = new TreeMap<>();
    }

    record Parsed(String factionKey, CrewFacts.Faction faction, String kind, int camo, int rng) {
    }
}
