package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.List;
import java.util.UUID;

import com.neoalive.tacz_sewv.crew.CrewFacts.Faction;
import com.neoalive.tacz_sewv.entity.ai.sensor.ContactBoard.Contact;
import com.neoalive.tacz_sewv.entity.ai.sensor.ContactBoard.Key;
import com.neoalive.tacz_sewv.entity.ai.sensor.ContactBoard.Source;

/**
 * Headless check of the contact board's store policy. Run via {@code ./gradlew selfCheckContactBoard}.
 * The Minecraft-facing half (keyOf, publish, waivesLos) needs a world and is argued in the docs instead.
 */
public final class ContactBoardSelfCheck {

    private static final int TTL = 200;
    private static final double MOVE = 6.0;
    private static final Key RU = new Key("minecraft:overworld", Faction.RU, null);

    public static void main(String[] args) {
        newContactIsWrittenOnce();
        pushOnChange();
        hearsayNeverRefreshes();
        upgradeAndNoDowngrade();
        expiryAndClockRewind();
        keysIsolate();
        orderingIsIdDerived();
        sweepReapsEmptyKeys();
        ttlOrdering();
        System.out.println("ContactBoardSelfCheck OK");
    }

    private static void reset() {
        ContactBoard.clearAll();
    }

    private static boolean up(Key k, int id, double x, Source s, long now) {
        return ContactBoard.upsert(k, id, x, 64, 0, s, now, TTL, MOVE);
    }

    private static void newContactIsWrittenOnce() {
        reset();
        assert up(RU, 5, 0, Source.DIRECT_SIGHT, 100) : "first publish must write";
        assert !up(RU, 5, 0, Source.DIRECT_SIGHT, 101) : "an identical republish must be a no-op";
        Contact c = ContactBoard.find(RU, 5, 101);
        assert c != null && c.firstSeen == 100 && c.expiresAt == 300;
    }

    private static void pushOnChange() {
        reset();
        up(RU, 5, 0, Source.DIRECT_SIGHT, 100);
        assert !up(RU, 5, MOVE - 0.5, Source.DIRECT_SIGHT, 110) : "a move under the threshold is not news";
        assert up(RU, 5, MOVE + 0.5, Source.DIRECT_SIGHT, 111) : "a move past the threshold is";
        assert ContactBoard.find(RU, 5, 111).expiresAt == 311 : "and it re-arms the deadline";
        // ageing: still at rest, but past half its life -> refreshed before it lapses
        assert !up(RU, 5, MOVE + 0.5, Source.DIRECT_SIGHT, 200) : "still young enough";
        assert up(RU, 5, MOVE + 0.5, Source.DIRECT_SIGHT, 215) : "past half life is re-armed";
    }

    private static void hearsayNeverRefreshes() {
        reset();
        up(RU, 7, 0, Source.RELAYED, 100);
        long expiry = ContactBoard.find(RU, 7, 100).expiresAt;
        assert !up(RU, 7, 50, Source.RELAYED, 110) : "an echo must not move the contact";
        assert !up(RU, 7, 0, Source.RELAYED, 155) : "nor keep it alive near expiry";
        assert ContactBoard.find(RU, 7, 155).expiresAt == expiry;
        assert ContactBoard.find(RU, 7, expiry) == null : "so it lapses on schedule";
    }

    private static void upgradeAndNoDowngrade() {
        reset();
        up(RU, 8, 0, Source.RELAYED, 100);
        assert up(RU, 8, 0, Source.DIRECT_SIGHT, 101) : "seeing it upgrades hearsay";
        assert ContactBoard.find(RU, 8, 101).source == Source.DIRECT_SIGHT;
        assert !up(RU, 8, 0, Source.PROXIMITY, 102) : "weaker evidence never downgrades";
        assert ContactBoard.find(RU, 8, 102).source == Source.DIRECT_SIGHT;
        assert Source.DIRECT_SIGHT.atLeast(Source.PROXIMITY) && !Source.RELAYED.atLeast(Source.PROXIMITY);
    }

    private static void expiryAndClockRewind() {
        reset();
        up(RU, 9, 0, Source.DIRECT_SIGHT, 100);
        assert ContactBoard.find(RU, 9, 299) != null;
        assert ContactBoard.find(RU, 9, 300) == null : "expired at its deadline";
        up(RU, 10, 0, Source.DIRECT_SIGHT, 100_000);
        // world reloaded: game time is back at 10, the stamp is in the future -> must not survive
        assert ContactBoard.find(RU, 10, 10) == null : "a deadline from a previous world must not stand";
        assert ContactBoard.live(RU, 10).isEmpty();
    }

    private static void keysIsolate() {
        reset();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Key pmcA = new Key("minecraft:overworld", Faction.PMC, a);
        Key pmcB = new Key("minecraft:overworld", Faction.PMC, b);
        Key pmcGarrison = new Key("minecraft:overworld", Faction.PMC, null);
        Key ruNether = new Key("minecraft:the_nether", Faction.RU, null);
        up(pmcA, 1, 0, Source.DIRECT_SIGHT, 0);
        assert ContactBoard.find(pmcB, 1, 1) == null : "one player's PMC must not learn what another's saw";
        assert ContactBoard.find(pmcGarrison, 1, 1) == null : "nor an ownerless garrison";
        assert ContactBoard.find(RU, 1, 1) == null : "nor another faction";
        assert ContactBoard.find(ruNether, 1, 1) == null : "nor another dimension";
        assert ContactBoard.find(pmcA, 1, 1) != null;
        assert new Key("minecraft:overworld", Faction.PMC, a).equals(pmcA) : "keys compare by value";
    }

    private static void orderingIsIdDerived() {
        reset();
        for (int id : new int[] {9, 3, 5, 1, 7}) up(RU, id, 0, Source.DIRECT_SIGHT, 0);
        List<Contact> live = ContactBoard.live(RU, 1);
        int prev = Integer.MIN_VALUE;
        for (Contact c : live) {
            assert c.entityId > prev : "board iteration must be ascending by entity id";
            prev = c.entityId;
        }
        assert live.size() == 5;
    }

    private static void sweepReapsEmptyKeys() {
        reset();
        Key gone = new Key("minecraft:overworld", Faction.PMC, UUID.randomUUID());
        up(gone, 1, 0, Source.DIRECT_SIGHT, 0);
        up(RU, 2, 0, Source.DIRECT_SIGHT, 0);
        ContactBoard.sweep(1000);
        assert ContactBoard.keyCount() == 0 : "an owner who left leaves nothing behind";
        up(RU, 2, 0, Source.DIRECT_SIGHT, 1000);
        ContactBoard.sweep(1001);
        assert ContactBoard.keyCount() == 1;
    }

    private static void ttlOrdering() {
        assert Source.DIRECT_SIGHT.ttl(TTL) > Source.PROXIMITY.ttl(TTL)
                && Source.PROXIMITY.ttl(TTL) > Source.RELAYED.ttl(TTL) && Source.RELAYED.ttl(TTL) > 0;
        assert Source.RELAYED.ttl(1) == 1 : "never a zero-tick contact";
    }

    private ContactBoardSelfCheck() {}
}
