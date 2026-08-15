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
 * Faction paint for SEM crews: the armor a unit wears <b>and</b> the uniform underneath it, held in
 * one table so the two can never disagree.
 *
 * <p>Filenames are {@code <faction>_<kind>[_<camo>[_<rng>]].png}. {@code kind} is either an armor
 * piece — the item registry path with a leading {@code us_}/{@code ru_} stripped, so
 * {@code superbwarfare:us_chest_iotv} → {@code chest_iotv} — or a body-skin category
 * ({@code infantry}, {@code medic}, {@code combat_engineer}, {@code mechanical_engineer}). The two
 * never collide, and sharing one table is what makes the camo sync <b>structural</b>: {@code camo}
 * means the same thing on both sides, so {@code us_chest_iotv_2_*} and {@code us_infantry_2_*} are
 * the same kit by construction rather than by two systems agreeing.
 *
 * <p><b>Armor decides the camo, the uniform follows.</b> {@link #resolveSetN} intersects the camo
 * ids available across the pieces actually worn and hashes the wearer's UUID to pick one; the body
 * skin is then looked up at that same camo. No art for it → null → SEM renders its own variant.
 * That fallback is the design, not a failure path: a camo only needs uniform art once someone draws
 * it.
 *
 * <p>Two trailing numbers are optional and both degrade cleanly. One number is read as
 * {@code camo} with {@code rng = 1}, which is exactly what the pre-camo naming
 * ({@code us_chest_iotv_1.png}) already meant — so an existing config folder keeps working and no
 * unit re-rolls.
 *
 * <p>Armor lives flat in {@code config/tacz_sewv/armor_skins/}; body skins live under
 * {@code config/tacz_sewv/skin_pools/}, which is scanned recursively. The subfolders there
 * ({@code infantry/}, {@code medics/}, …) are for the person browsing the folder — the category is
 * read off the filename, so a misfiled PNG still loads.
 */
@OnlyIn(Dist.CLIENT)
public final class CrewSkinRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String LOG_PREFIX = "[sewv-crew-skins]";

    private static final String ARMOR_DEFAULTS = "armor_skins_defaults";
    private static final String SKIN_POOL_DEFAULTS = "skin_pools_defaults";

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
    private static final List<ResourceLocation> REGISTERED = new ArrayList<>();

    private CrewSkinRegistry() {
    }

    public static Path armorDirectory() {
        return FMLPaths.CONFIGDIR.get().resolve(TaczSewv.MODID).resolve("armor_skins");
    }

    public static Path skinPoolDirectory() {
        return FMLPaths.CONFIGDIR.get().resolve(TaczSewv.MODID).resolve("skin_pools");
    }

    /** Release prior dynamics and re-scan both folders. Safe to call repeatedly. */
    public static synchronized void reload(ResourceManager resources) {
        TextureManager textures = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation id : REGISTERED) {
            textures.release(id);
        }
        REGISTERED.clear();
        ENTRIES.clear();

        load(armorDirectory(), ARMOR_DEFAULTS, resources, false);
        load(skinPoolDirectory(), SKIN_POOL_DEFAULTS, resources, true);

        LOGGER.info("{} loaded {} skin file(s) / {} faction+kind entries", LOG_PREFIX,
                REGISTERED.size(), ENTRIES.size());
    }

    /**
     * Throw away whatever is on disk and put the jar's own art back. Deleting first is what makes
     * this a real reset rather than a top-up: it also clears pre-camo duplicates, where an old
     * {@code us_chest_iotv_1.png} would otherwise sit beside the seeded {@code us_chest_iotv_1_1.png}
     * as a second RNG entry for the same camo. Hand-added camos go with it.
     */
    public static synchronized void resetToDefaults(ResourceManager resources) {
        SkinFiles.wipe(armorDirectory(), LOG_PREFIX);
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
                // Index-walk rather than copying to a list: this runs per armor piece per frame.
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
     * applies (plain-only kit / no skins). Intersection of camo ids across worn pieces that have
     * any, with the UUID picking one from that intersection.
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
        return pick(intersection, wearer);
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
        return get(kind, faction, resolveSetN(wearer, faction), wearer.getUUID().hashCode());
    }

    /**
     * The unit's uniform, or null to let SEM pick its own variant.
     *
     * <p>The camo comes from the armor so the two match. A unit wearing nothing numbered — armor
     * disabled, or a support unit issued headwear only — falls back to the category's own camos
     * rather than going unskinned; there is nothing to stay in step with in that case.
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
        return get(category, faction, camo, unit.getUUID().hashCode());
    }

    /**
     * Which uniform pool a unit draws from. RU/US carry the role in their entity type; a PMC cannot
     * — it is a unit the player re-tasks in the field — so its role is whatever {@link SupportRole}
     * reads out of its hands, and its uniform changes when the tool does. Splitting on faction
     * rather than falling through keeps an RU rifleman handed a medical kit a rifleman, and keeps
     * the item scan off the common case.
     */
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

    /** One camo id out of a set, stable per unit; {@code -1} for an empty or absent set. */
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
            LOGGER.info("{} skipped invalid file: {}", LOG_PREFIX, name);
            return;
        }

        String dynPath = ("dynamic/crew_skins/" + parsed.factionKey + "_" + parsed.kind
                + (parsed.camo < 0 ? "" : "_" + parsed.camo + "_" + parsed.rng) + ".png")
                .toLowerCase(Locale.ROOT);
        ResourceLocation id = new ResourceLocation(TaczSewv.MODID, dynPath);
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(in);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
            REGISTERED.add(id);
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
        } catch (Exception e) {
            LOGGER.info("{} skipped invalid file: {} ({})", LOG_PREFIX, name, e.toString());
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

    /** Value of the trailing {@code _<digits>} segment, or {@code -1} if there isn't one. */
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
        /** camo id → rng id → texture. Sorted both ways so selection is order-independent. */
        final TreeMap<Integer, TreeMap<Integer, ResourceLocation>> pools = new TreeMap<>();
    }

    record Parsed(String factionKey, CrewFacts.Faction faction, String kind, int camo, int rng) {
    }
}
