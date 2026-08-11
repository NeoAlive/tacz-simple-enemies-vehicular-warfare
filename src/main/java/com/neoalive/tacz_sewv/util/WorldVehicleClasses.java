package com.neoalive.tacz_sewv.util;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * Per-world vehicle class cues and faction armor loadouts. Edited via {@code /sewv pool misc}.
 * COMMON config remains the seed for armor; clue lists seed from shipped defaults.
 */
public class WorldVehicleClasses extends SavedData {

    private static final String DATA_NAME = "tacz_sewv_vehicle_classes";

    /**
     * The four {@code PLANE_*} lists classify an <b>aircraft weapon slot</b>, and they are matched
     * against the slot's name and its ammo item id together. Both halves are needed: SBW names the
     * A-10's slots {@code Cannon}/{@code Rocket}/{@code Bomb}/{@code Missile}, which is readable,
     * but the Ju-87 calls its gun {@code MachineGun} and addon packs name slots whatever they like
     * — whereas the ammo id ({@code small_shell_ap}, {@code small_rocket}, {@code
     * medium_aerial_bomb}, {@code large_anti_ground_missile}) is real datapack metadata that says
     * what the round actually is. The lists are consulted in the order GUIDED, BOMB, ROCKET,
     * CANNON, so a longer clue can carve a special case out of a broader one.
     */
    public enum CueKind {
        IFV,
        ANTI_AIR,
        MISSILE_SYSTEM,
        ARTILLERY,
        PLANE_MISSILE,
        PLANE_BOMB,
        PLANE_ROCKET,
        PLANE_CANNON
    }

    private static final List<String> DEFAULT_IFV = List.of(
            "bradley", "bmp", "bmd", "cv90", "puma", "marder");
    private static final List<String> DEFAULT_ANTI_AIR = List.of(
            "gepard", "pantsir", "pa_pantsir");
    private static final List<String> DEFAULT_MISSILE = List.of("sapsan", "grim2");
    private static final List<String> DEFAULT_ARTILLERY = List.of(
            "plz_05", "mk_42", "mle_1934", "bl_132");
    private static final List<String> DEFAULT_PLANE_MISSILE = List.of(
            "missile", "anti_ground_missile", "anti_air_missile", "agm", "kh_", "atgm", "maverick");
    private static final List<String> DEFAULT_PLANE_BOMB = List.of(
            "bomb", "aerial_bomb", "mortar_shell");
    private static final List<String> DEFAULT_PLANE_ROCKET = List.of(
            "rocket", "small_rocket", "hydra");
    private static final List<String> DEFAULT_PLANE_CANNON = List.of(
            "cannon", "machinegun", "gau", "shell_ap", "shell_he", "shell_aa",
            "rifleammo", "heavyammo");

    private final Map<CueKind, List<String>> cues = new EnumMap<>(CueKind.class);
    private final Map<TankFaction, List<String>> armor = new EnumMap<>(TankFaction.class);

    public WorldVehicleClasses() {
        seedDefaults();
    }

    public static WorldVehicleClasses load(CompoundTag nbt) {
        WorldVehicleClasses data = new WorldVehicleClasses();
        data.cues.clear();
        data.armor.clear();
        for (CueKind kind : CueKind.values()) {
            String key = "cue_" + kind.name().toLowerCase(Locale.ROOT);
            if (nbt.contains(key, Tag.TAG_LIST)) {
                data.cues.put(kind, readList(nbt.getList(key, Tag.TAG_STRING)));
            } else {
                data.cues.put(kind, new ArrayList<>(builtInCues(kind)));
            }
        }
        for (TankFaction faction : TankFaction.values()) {
            String key = "armor_" + faction.name().toLowerCase(Locale.ROOT);
            if (nbt.contains(key, Tag.TAG_LIST)) {
                data.armor.put(faction, readList(nbt.getList(key, Tag.TAG_STRING)));
            } else {
                data.armor.put(faction, new ArrayList<>(builtInArmor(faction)));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        for (CueKind kind : CueKind.values()) {
            nbt.put("cue_" + kind.name().toLowerCase(Locale.ROOT), writeList(listCues(kind)));
        }
        for (TankFaction faction : TankFaction.values()) {
            nbt.put("armor_" + faction.name().toLowerCase(Locale.ROOT), writeList(listArmor(faction)));
        }
        return nbt;
    }

    public static WorldVehicleClasses get(Level level) {
        if (level instanceof ServerLevel serverLevel) {
            ServerLevel overworld = serverLevel.getServer().getLevel(Level.OVERWORLD);
            if (overworld != null) {
                return overworld.getDataStorage().computeIfAbsent(
                        WorldVehicleClasses::load,
                        WorldVehicleClasses::new,
                        DATA_NAME);
            }
        }
        return new WorldVehicleClasses();
    }

    public List<String> listCues(CueKind kind) {
        return cues.computeIfAbsent(kind, k -> new ArrayList<>(builtInCues(k)));
    }

    public void setCues(CueKind kind, List<String> ids) {
        cues.put(kind, new ArrayList<>(ids));
        setDirty();
    }

    public List<String> listArmor(TankFaction faction) {
        return armor.computeIfAbsent(faction, f -> new ArrayList<>(builtInArmor(f)));
    }

    public void setArmor(TankFaction faction, List<String> ids) {
        armor.put(faction, new ArrayList<>(ids));
        setDirty();
    }

    public void resetCues(CueKind kind) {
        setCues(kind, builtInCues(kind));
    }

    public void resetArmor(TankFaction faction) {
        setArmor(faction, builtInArmor(faction));
    }

    private void seedDefaults() {
        for (CueKind kind : CueKind.values()) {
            cues.put(kind, new ArrayList<>(builtInCues(kind)));
        }
        for (TankFaction faction : TankFaction.values()) {
            armor.put(faction, new ArrayList<>(builtInArmor(faction)));
        }
    }

    public static List<String> builtInCues(CueKind kind) {
        return switch (kind) {
            case IFV -> DEFAULT_IFV;
            case ANTI_AIR -> DEFAULT_ANTI_AIR;
            case MISSILE_SYSTEM -> DEFAULT_MISSILE;
            case ARTILLERY -> DEFAULT_ARTILLERY;
            case PLANE_MISSILE -> DEFAULT_PLANE_MISSILE;
            case PLANE_BOMB -> DEFAULT_PLANE_BOMB;
            case PLANE_ROCKET -> DEFAULT_PLANE_ROCKET;
            case PLANE_CANNON -> DEFAULT_PLANE_CANNON;
        };
    }

    public static List<String> builtInArmor(TankFaction faction) {
        try {
            return switch (faction) {
                case RU -> copyConfig(SewvConfig.RU_ARMOR.get());
                case US -> copyConfig(SewvConfig.US_ARMOR.get());
                case PMC -> copyConfig(SewvConfig.PMC_ARMOR.get());
            };
        } catch (Throwable ignored) {
            return switch (faction) {
                case RU -> List.of("superbwarfare:ru_helmet_6b47", "superbwarfare:ru_chest_6b43");
                case US, PMC -> List.of("superbwarfare:us_helmet_pasgt", "superbwarfare:us_chest_iotv");
            };
        }
    }

    private static List<String> copyConfig(List<? extends String> src) {
        List<String> out = new ArrayList<>(src.size());
        for (String s : src) {
            if (s != null && !s.isBlank()) out.add(s);
        }
        return out;
    }

    private static List<String> readList(ListTag tags) {
        List<String> list = new ArrayList<>(tags.size());
        for (int i = 0; i < tags.size(); i++) list.add(tags.getString(i));
        return list;
    }

    private static ListTag writeList(List<String> ids) {
        ListTag tags = new ListTag();
        for (String id : ids) tags.add(StringTag.valueOf(id));
        return tags;
    }
}
