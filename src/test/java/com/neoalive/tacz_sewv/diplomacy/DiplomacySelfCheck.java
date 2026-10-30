package com.neoalive.tacz_sewv.diplomacy;

import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import com.neoalive.tacz_sewv.diplomacy.DiplomacyData.Entry;
import com.neoalive.tacz_sewv.diplomacy.DiplomacyData.Relation;
import com.neoalive.tacz_sewv.diplomacy.DiplomacyData.Subject;

/**
 * Headless checks for {@link DiplomacyData}'s subjects, storage and invasion overlay. Plain
 * {@code main} + assert, like the other self-checks. Run with {@code ./gradlew selfCheckDiplomacy}.
 */
public final class DiplomacySelfCheck {

    public static void main(String[] args) {
        legacyNbtLoadsAsFactions();
        keysIgnoreOrderAndCase();
        playerAndFactionDoNotCollide();
        overlayWinsAndClears();
        resolveOrder();
        sideBeatsPartyAndDoesNotCollide();
        saveRoundTrip();
        System.out.println("DiplomacySelfCheck OK");
    }

    /** Saves from before player diplomacy carry no kind fields: every name was a party. */
    private static void legacyNbtLoadsAsFactions() {
        CompoundTag pair = new CompoundTag();
        pair.putString("a", "Reds");
        pair.putString("b", "Blues");
        pair.putString("rel", "ENEMY");
        ListTag list = new ListTag();
        list.add(pair);
        CompoundTag nbt = new CompoundTag();
        nbt.put("pairs", list);
        DiplomacyData data = DiplomacyData.load(nbt);
        check(data.relation("Reds", "Blues") == Relation.ENEMY, "legacy pair lost its faction meaning");
        check(data.relation(Subject.player("Reds"), Subject.player("Blues")) == Relation.NEUTRAL,
                "a legacy faction pair leaked into player names");
    }

    private static void keysIgnoreOrderAndCase() {
        DiplomacyData data = new DiplomacyData();
        check(data.set(Subject.player("Alice"), Subject.player("Bob"), Relation.ENEMY), "set refused");
        check(data.relation(Subject.player("bob"), Subject.player("ALICE")) == Relation.ENEMY,
                "lookup depends on order or case");
        check(!data.set(Subject.player("Alice"), Subject.player("alice"), Relation.ENEMY),
                "a subject was allowed an enemy relation with itself");
        check(data.relation(Subject.player("Alice"), Subject.player("alice")) == Relation.ALLY,
                "the same subject must read as allied");
        check(data.remove(Subject.player("BOB"), Subject.player("alice")), "remove missed the pair");
        check(data.isEmpty(), "remove left data behind");
    }

    private static void playerAndFactionDoNotCollide() {
        DiplomacyData data = new DiplomacyData();
        data.set("Alice", "Bob", Relation.ENEMY);
        check(data.relation(Subject.player("Alice"), Subject.player("Bob")) == Relation.NEUTRAL,
                "a faction pair answered for players of the same names");
    }

    private static void overlayWinsAndClears() {
        DiplomacyData data = new DiplomacyData();
        data.set(Subject.player("Alice"), Subject.player("Bob"), Relation.ALLY);
        data.setInvasion(List.of(new Entry(Subject.player("Alice"), Subject.player("Bob"), Relation.ENEMY, true)));
        check(data.relation(Subject.player("Alice"), Subject.player("Bob")) == Relation.ENEMY,
                "the invasion overlay must override a manual relation in-match");
        check(data.snapshot().size() == 2, "list should show both the manual and the overlay entry");
        data.clearInvasion();
        check(data.relation(Subject.player("Alice"), Subject.player("Bob")) == Relation.ALLY,
                "clearing the overlay did not restore the manual relation");

        DiplomacyData overlayOnly = new DiplomacyData();
        overlayOnly.setInvasion(List.of(new Entry(Subject.player("A"), Subject.player("B"), Relation.ENEMY, true)));
        check(!overlayOnly.isEmpty(), "isEmpty ignored the overlay");
        CompoundTag saved = overlayOnly.save(new CompoundTag());
        check(saved.getList("pairs", 10).isEmpty(), "the invasion overlay must never be saved");
    }

    /** Player pairs are more specific than party pairs and must win. */
    private static void resolveOrder() {
        DiplomacyData data = new DiplomacyData();
        data.set("Reds", "Blues", Relation.ENEMY);
        data.set(Subject.player("Alice"), Subject.player("Bob"), Relation.ALLY);
        List<Subject> alice = List.of(Subject.player("Alice"), Subject.faction("Reds"));
        List<Subject> bob = List.of(Subject.player("Bob"), Subject.faction("Blues"));
        List<Subject> carol = List.of(Subject.player("Carol"), Subject.faction("Blues"));
        check(data.resolve(alice, bob) == Relation.ALLY, "the faction pair beat the player pair");
        check(data.resolve(alice, carol) == Relation.ENEMY, "the faction pair was not the fallback");
        check(data.resolve(List.of(Subject.player("Dan")), List.of(Subject.player("Eve"))) == null,
                "an unrelated pair must resolve to nothing stored");
        data.set(Subject.player("Alice"), Subject.faction("Blues"), Relation.ALLY);
        check(data.resolve(alice, carol) == Relation.ALLY, "player↔faction must beat faction↔faction");
    }

    /**
     * A SEM side is its own subject. A personal pair beats the party's treaty, an unset pair is
     * not an alliance, and a party named "ru" is not the RU side.
     */
    private static void sideBeatsPartyAndDoesNotCollide() {
        DiplomacyData data = new DiplomacyData();
        check(data.resolve(List.of(Subject.player("Alice")), List.of(Subject.side("ru"))) == null,
                "an unset side pair must resolve to nothing stored, not ALLY");
        check(data.relation(Subject.player("Alice"), Subject.side("ru")) == Relation.NEUTRAL,
                "absence still reads as neutral");

        data.set(Subject.faction("Reds"), Subject.side("ru"), Relation.ALLY);
        data.set(Subject.player("Alice"), Subject.side("RU"), Relation.ENEMY);
        List<Subject> alice = List.of(Subject.player("Alice"), Subject.faction("Reds"));
        List<Subject> bob = List.of(Subject.player("Bob"), Subject.faction("Reds"));
        check(data.resolve(alice, List.of(Subject.side("ru"))) == Relation.ENEMY,
                "a personal side pair must beat the party treaty");
        check(data.resolve(bob, List.of(Subject.side("ru"))) == Relation.ALLY,
                "the party treaty must cover a member with no personal pair");

        data.set(Subject.faction("ru"), Subject.faction("us"), Relation.ENEMY);
        check(data.relation(Subject.side("ru"), Subject.side("us")) == Relation.NEUTRAL,
                "a party named ru collided with the SEM side");
        check(data.resolve(alice, List.of(Subject.side("ru"))) == Relation.ENEMY,
                "adding a party pair changed the side resolve");
    }

    private static void saveRoundTrip() {
        DiplomacyData data = new DiplomacyData();
        data.set(Subject.player("Alice"), Subject.faction("Blues"), Relation.ENEMY);
        data.set(Subject.player("Alice"), Subject.side("us"), Relation.ALLY);
        DiplomacyData loaded = DiplomacyData.load(data.save(new CompoundTag()));
        check(loaded.relation(Subject.player("alice"), Subject.faction("BLUES")) == Relation.ENEMY,
                "kinds did not survive a save/load");
        check(loaded.relation(Subject.player("Alice"), Subject.side("US")) == Relation.ALLY,
                "a side pair did not survive a save/load");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
