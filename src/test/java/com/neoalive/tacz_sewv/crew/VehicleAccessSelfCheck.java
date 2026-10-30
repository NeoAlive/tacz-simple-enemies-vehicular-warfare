package com.neoalive.tacz_sewv.crew;

import java.util.List;
import java.util.UUID;

import com.neoalive.tacz_sewv.crew.VehicleAccess.Facts;
import com.neoalive.tacz_sewv.crew.VehicleAccess.Kind;
import com.neoalive.tacz_sewv.crew.VehicleAccess.Occupant;
import com.neoalive.tacz_sewv.crew.VehicleAccess.Stamp;
import com.neoalive.tacz_sewv.crew.VehicleAccess.Viewer;
import com.neoalive.tacz_sewv.diplomacy.DiplomacyData;
import com.neoalive.tacz_sewv.diplomacy.DiplomacyData.Relation;
import com.neoalive.tacz_sewv.diplomacy.DiplomacyData.Subject;

/**
 * Headless checks for {@link VehicleAccess}. Run with {@code ./gradlew selfCheckVehicleAccess}.
 */
public final class VehicleAccessSelfCheck {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    public static void main(String[] args) {
        Viewer alice = new Viewer(A, null);
        Viewer aliceRed = new Viewer(A, "Red");
        Occupant bobsPmc = new Occupant(Kind.PMC, B, null);
        Occupant ru = new Occupant(Kind.RU, null, null);

        check(VehicleAccess.denies(alice, facts(List.of(bobsPmc), null), null, null),
                "a foreign PMC passenger must deny");
        check(VehicleAccess.denies(alice, facts(List.of(), new Stamp(B, null)), Relation.ALLY, Relation.ALLY),
                "an empty hull stamped to someone else must deny, alliance or not");
        check(!VehicleAccess.denies(alice, facts(List.of(), new Stamp(A, null)), null, null),
                "the stamped owner must be allowed");
        check(!VehicleAccess.denies(aliceRed, facts(List.of(), new Stamp(B, "Red")), null, null),
                "a stamped team must admit a member");
        check(VehicleAccess.denies(alice, facts(List.of(), new Stamp(B, "Red")), null, null),
                "a team stamp must deny a player who is not on that team");
        check(!VehicleAccess.denies(alice, facts(List.of(new Occupant(Kind.PMC, null, null)), null), null, null),
                "an ownerless garrison crew must not lock the hull");

        check(VehicleAccess.denies(alice, facts(List.of(ru), null), null, null),
                "an unset RU relation must stay locked");
        check(VehicleAccess.denies(alice, facts(List.of(ru), null), Relation.ENEMY, null),
                "ENEMY must lock an RU crew");
        check(!VehicleAccess.denies(alice, facts(List.of(ru), null), Relation.ALLY, null),
                "ALLY must open an RU crew");
        check(VehicleAccess.denies(alice, facts(List.of(new Occupant(Kind.US, null, null)), null), Relation.ALLY, null),
                "an RU alliance must not open a US crew");

        check(VehicleAccess.denies(alice, facts(List.of(bobsPmc), null), Relation.ALLY, Relation.ALLY),
                "alliance with a side, or with the other player, must not open their PMC hull");

        DiplomacyData data = new DiplomacyData();
        data.set(Subject.faction("Reds"), Subject.side("ru"), Relation.ALLY);
        data.set(Subject.player("Alice"), Subject.side("ru"), Relation.ENEMY);
        Relation aliceRu = data.resolve(
                List.of(Subject.player("Alice"), Subject.faction("Reds")), List.of(Subject.side("ru")));
        Relation bobRu = data.resolve(
                List.of(Subject.player("Bob"), Subject.faction("Reds")), List.of(Subject.side("ru")));
        check(aliceRu == Relation.ENEMY, "personal ENEMY did not beat the party ALLY");
        check(VehicleAccess.denies(alice, facts(List.of(ru), null), aliceRu, null),
                "the resolved personal ENEMY must lock the hull");
        check(bobRu == Relation.ALLY, "the party ALLY did not cover the member without a personal pair");
        check(!VehicleAccess.denies(new Viewer(B, null), facts(List.of(ru), null), bobRu, null),
                "the resolved party ALLY must open the hull");

        System.out.println("VehicleAccessSelfCheck OK");
    }

    private static Facts facts(List<Occupant> occupants, Stamp stamp) {
        return new Facts(occupants, stamp);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
