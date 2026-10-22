package com.neoalive.tacz_sewv.diplomacy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import com.neoalive.tacz_sewv.debug.SewvDiag;

/**
 * SEWV-only diplomacy between <b>subjects</b>: OpenPAC party names ({@link Kind#FACTION}) or
 * player names ({@link Kind#PLAYER}, the scoreboard/profile name {@code /team} holds). Relationships
 * are never written back to OpenPAC. Absence of a pair = {@link Relation#NEUTRAL} (not stored).
 *
 * <p>Two layers. {@link #pairs} is the saved, manual layer ({@code /sewv diplomacy}). {@link #invasion}
 * is a transient overlay owned by {@code InvasionDiplomacy}: it is never saved and wins over the manual
 * layer while it holds a pair, so a running match can put two manually allied players on opposite
 * sides and the manual entry comes back untouched once the match clears it. A restart force-ends any
 * invasion anyway, so an unsaved overlay cannot outlive its match.
 *
 * <p>The {@code String} overloads keep their original faction meaning — territory reads them.
 */
public class DiplomacyData extends SavedData {

    private static final String DATA_NAME = "tacz_sewv_diplomacy";

    public enum Relation {
        NEUTRAL, ALLY, ENEMY
    }

    public enum Kind {
        FACTION, PLAYER;

        String prefix() {
            return this == FACTION ? "f:" : "p:";
        }
    }

    /** One side of a pair. Names compare case-insensitively. */
    public record Subject(Kind kind, String name) {
        public static Subject faction(String name) {
            return new Subject(Kind.FACTION, name);
        }

        public static Subject player(String name) {
            return new Subject(Kind.PLAYER, name);
        }

        String key() {
            return kind.prefix() + name.trim().toLowerCase(Locale.ROOT);
        }

        boolean blank() {
            return name == null || name.isBlank();
        }

        @Override
        public String toString() {
            return kind.name().toLowerCase(Locale.ROOT) + ":" + name;
        }
    }

    /** A stored pair as given (display names kept), keyed canonically elsewhere. */
    public record Entry(Subject a, Subject b, Relation relation, boolean invasion) {
        public String format() {
            return a + " ↔ " + b + " = " + relation.name().toLowerCase(Locale.ROOT)
                    + (invasion ? " [invasion]" : "");
        }
    }

    /** Manual layer: canonical key → entry. */
    private final Map<String, Entry> pairs = new HashMap<>();
    /** Invasion overlay, transient: canonical key → entry. */
    private final Map<String, Entry> invasion = new HashMap<>();

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
            // Entries saved before player diplomacy carry no kind: they were all party names.
            Subject sa = new Subject(kindOf(tag.getString("ak")), a);
            Subject sb = new Subject(kindOf(tag.getString("bk")), b);
            data.pairs.put(canonicalKey(sa, sb), new Entry(sa, sb, relation, false));
        }
        SewvDiag.diplomacy("SavedData LOAD pairs={} contents={}", data.pairs.size(), data.pairs.keySet());
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry e : pairs.values()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("a", e.a().name());
            tag.putString("b", e.b().name());
            tag.putString("ak", e.a().kind().name());
            tag.putString("bk", e.b().kind().name());
            tag.putString("rel", e.relation().name());
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
                DiplomacyData data = overworld.getDataStorage().computeIfAbsent(new SavedData.Factory<>(DiplomacyData::new, (tag, registries) -> DiplomacyData.load(tag)), DATA_NAME);
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

    // ---- lookups -------------------------------------------------------------------------------

    /** Faction ↔ faction, the original meaning (territory reads this). */
    public Relation relation(String factionA, String factionB) {
        if (factionA == null || factionB == null) return Relation.NEUTRAL;
        return relation(Subject.faction(factionA), Subject.faction(factionB));
    }

    /** One pair; the invasion overlay wins over the manual layer. Same subject = ALLY. */
    public Relation relation(Subject a, Subject b) {
        if (a == null || b == null || a.blank() || b.blank()) return Relation.NEUTRAL;
        if (a.key().equals(b.key())) return Relation.ALLY;
        Relation r = find(canonicalKey(a, b));
        return r != null ? r : Relation.NEUTRAL;
    }

    /**
     * First stored relation between any subject of {@code self} and any of {@code other}, most
     * specific first: player↔player, player↔faction, faction↔player, faction↔faction. Each side's
     * list carries at most one subject of each kind. Null when nothing is stored for any pairing.
     */
    @Nullable
    public Relation resolve(List<Subject> self, List<Subject> other) {
        for (Kind[] order : RESOLVE_ORDER) {
            Subject a = firstOfKind(self, order[0]);
            Subject b = firstOfKind(other, order[1]);
            if (a == null || b == null) continue;
            if (a.key().equals(b.key())) return Relation.ALLY;
            Relation r = find(canonicalKey(a, b));
            if (r != null) return r;
        }
        return null;
    }

    private static final Kind[][] RESOLVE_ORDER = {
            {Kind.PLAYER, Kind.PLAYER}, {Kind.PLAYER, Kind.FACTION},
            {Kind.FACTION, Kind.PLAYER}, {Kind.FACTION, Kind.FACTION}};

    /** No manual pairs and no invasion overlay — the one-check gate in front of entity scans. */
    public boolean isEmpty() {
        return pairs.isEmpty() && invasion.isEmpty();
    }

    @Nullable
    private Relation find(String key) {
        Entry e = invasion.get(key);
        if (e == null) e = pairs.get(key);
        return e == null ? null : e.relation();
    }

    @Nullable
    private static Subject firstOfKind(List<Subject> subjects, Kind kind) {
        for (Subject s : subjects) {
            if (s != null && s.kind() == kind && !s.blank()) return s;
        }
        return null;
    }

    // ---- manual layer --------------------------------------------------------------------------

    /** Faction ↔ faction ALLY or ENEMY; NEUTRAL removes. */
    public boolean set(String factionA, String factionB, Relation relation) {
        return set(Subject.faction(factionA), Subject.faction(factionB), relation);
    }

    /** Sets ALLY or ENEMY (NEUTRAL removes). Returns false if a name is blank or both are the same subject. */
    public boolean set(Subject a, Subject b, Relation relation) {
        if (relation == Relation.NEUTRAL) return remove(a, b);
        if (a == null || b == null || a.blank() || b.blank() || a.key().equals(b.key())) {
            SewvDiag.diplomacy("set FAILED blankOrSame a={} b={} rel={}", a, b, relation);
            return false;
        }
        Subject ta = new Subject(a.kind(), a.name().trim());
        Subject tb = new Subject(b.kind(), b.name().trim());
        String key = canonicalKey(ta, tb);
        pairs.put(key, new Entry(ta, tb, relation, false));
        setDirty();
        SewvDiag.diplomacy("set OK key={} rel={} pairsNow={}", key.replace('\0', '|'), relation, pairs.size());
        return true;
    }

    public boolean remove(String factionA, String factionB) {
        return remove(Subject.faction(factionA), Subject.faction(factionB));
    }

    public boolean remove(Subject a, Subject b) {
        if (a == null || b == null || a.blank() || b.blank()) return false;
        if (pairs.remove(canonicalKey(a, b)) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    // ---- invasion overlay ----------------------------------------------------------------------

    /** Replaces the whole overlay. Never saved; see the class doc. */
    public void setInvasion(List<Entry> entries) {
        invasion.clear();
        for (Entry e : entries) {
            if (e.a().blank() || e.b().blank() || e.a().key().equals(e.b().key())) continue;
            invasion.put(canonicalKey(e.a(), e.b()), new Entry(e.a(), e.b(), e.relation(), true));
        }
        SewvDiag.diplomacy("invasion overlay SET entries={}", invasion.size());
    }

    public void clearInvasion() {
        if (invasion.isEmpty()) return;
        invasion.clear();
        SewvDiag.diplomacy("invasion overlay CLEARED");
    }

    /**
     * Manual pairs, then overlay pairs, for listing. Both layers are listed even for the same pair,
     * so a manual relation an invasion is overriding still shows as kept, not lost.
     */
    public List<Entry> snapshot() {
        List<Entry> out = new ArrayList<>(pairs.values());
        out.addAll(invasion.values());
        return List.copyOf(out);
    }

    // ---- keys ----------------------------------------------------------------------------------

    static String canonicalKey(Subject a, Subject b) {
        String x = a.key();
        String y = b.key();
        return x.compareTo(y) <= 0 ? x + '\0' + y : y + '\0' + x;
    }

    private static Kind kindOf(String stored) {
        return "PLAYER".equals(stored) ? Kind.PLAYER : Kind.FACTION;
    }
}
