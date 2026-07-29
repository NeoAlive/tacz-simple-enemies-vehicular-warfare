package com.neoalive.tacz_sewv.diplomacy;

import com.neoalive.tacz_sewv.debug.SewvDiag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * SEWV-only faction diplomacy (Stage 4). OpenPAC names identify factions; relationships are never
 * written back to OpenPAC. Absence of a pair = {@link Relation#NEUTRAL} (not stored).
 */
public class DiplomacyData extends SavedData {

    private static final String DATA_NAME = "tacz_sewv_diplomacy";

    public enum Relation {
        NEUTRAL, ALLY, ENEMY
    }

    /** Canonical key {@code a\0b} with a &lt; b (case-insensitive compare, stored as given). */
    private final Map<String, Relation> pairs = new HashMap<>();

    public DiplomacyData() {}

    public static DiplomacyData load(CompoundTag nbt) {
        DiplomacyData data = new DiplomacyData();
        ListTag list = nbt.getList("pairs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            String a = tag.getString("a");
            String b = tag.getString("b");
            String rel = tag.getString("rel");
            if (a.isEmpty() || b.isEmpty()) continue;
            Relation relation = "ALLY".equals(rel) ? Relation.ALLY
                    : "ENEMY".equals(rel) ? Relation.ENEMY : null;
            if (relation == null) continue;
            data.pairs.put(canonicalKey(a, b), relation);
        }
        SewvDiag.diplomacy("SavedData LOAD pairs={} contents={}", data.pairs.size(), data.pairs);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        ListTag list = new ListTag();
        for (Map.Entry<String, Relation> e : pairs.entrySet()) {
            String[] ab = splitKey(e.getKey());
            CompoundTag tag = new CompoundTag();
            tag.putString("a", ab[0]);
            tag.putString("b", ab[1]);
            tag.putString("rel", e.getValue().name());
            list.add(tag);
        }
        nbt.put("pairs", list);
        SewvDiag.diplomacy("SavedData SAVE pairs={}", pairs.size());
        return nbt;
    }

    public static DiplomacyData get(Level level) {
        if (level instanceof ServerLevel serverLevel) {
            ServerLevel overworld = serverLevel.getServer().getLevel(Level.OVERWORLD);
            if (overworld != null) {
                DiplomacyData data = overworld.getDataStorage().computeIfAbsent(
                        DiplomacyData::load, DiplomacyData::new, DATA_NAME);
                // First get after create: empty ctor path logs once via identity.
                return data;
            }
            SewvDiag.diplomacy("get() overworld=NULL — returning EMPTY ephemeral instance");
        } else {
            SewvDiag.diplomacy("get() level not ServerLevel ({}) — returning EMPTY ephemeral instance",
                    level == null ? "null" : level.getClass().getSimpleName());
        }
        return new DiplomacyData();
    }

    public Relation relation(String factionA, String factionB) {
        if (factionA == null || factionB == null) return Relation.NEUTRAL;
        if (factionA.equalsIgnoreCase(factionB)) return Relation.ALLY;
        Relation r = pairs.get(canonicalKey(factionA, factionB));
        return r != null ? r : Relation.NEUTRAL;
    }

    /** Sets ALLY or ENEMY. Returns false if names are blank or identical. */
    public boolean set(String factionA, String factionB, Relation relation) {
        if (relation == Relation.NEUTRAL) return remove(factionA, factionB);
        if (blank(factionA) || blank(factionB) || factionA.equalsIgnoreCase(factionB)) {
            SewvDiag.diplomacy("set FAILED blankOrSame a={} b={} rel={}", factionA, factionB, relation);
            return false;
        }
        String key = canonicalKey(factionA, factionB);
        pairs.put(key, relation);
        setDirty();
        SewvDiag.diplomacy("set OK key={} rel={} pairsNow={}", key.replace('\0', '|'), relation, pairs);
        return true;
    }

    public boolean remove(String factionA, String factionB) {
        if (blank(factionA) || blank(factionB)) return false;
        if (pairs.remove(canonicalKey(factionA, factionB)) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    public Map<String, Relation> snapshot() {
        return Map.copyOf(pairs);
    }

    private static boolean blank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    static String canonicalKey(String a, String b) {
        String x = a.trim();
        String y = b.trim();
        if (x.compareToIgnoreCase(y) <= 0) return x + '\0' + y;
        return y + '\0' + x;
    }

    private static String[] splitKey(String key) {
        int i = key.indexOf('\0');
        if (i < 0) return new String[]{key, key};
        return new String[]{key.substring(0, i), key.substring(i + 1)};
    }

    /** Display form of a stored key for listing. */
    public static String formatPair(String key, Relation rel) {
        String[] ab = splitKey(key);
        return ab[0] + " ↔ " + ab[1] + " = " + rel.name().toLowerCase(Locale.ROOT);
    }
}
