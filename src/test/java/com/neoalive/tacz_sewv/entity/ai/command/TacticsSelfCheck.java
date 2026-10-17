package com.neoalive.tacz_sewv.entity.ai.command;

import java.util.Map;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import com.neoalive.tacz_sewv.entity.ai.command.Assignment.FlankSide;
import com.neoalive.tacz_sewv.entity.ai.command.Assignment.Role;
import com.neoalive.tacz_sewv.entity.ai.formation.FormationTemplates;

/**
 * Headless check of the group tactic post-processes: leapfrog, formation slots, commander back, cover points.
 * Run via {@code ./gradlew selfCheckTactics}.
 */
public final class TacticsSelfCheck {

    public static void main(String[] args) {
        boolean on = false;
        assert on = true;
        if (!on) throw new IllegalStateException("run with -ea");
        leapfrog();
        formationSlots();
        shippedFormations();
        commanderBack();
        coverPoints();
        System.out.println("TacticsSelfCheck OK");
    }

    private static Assignment a(int id, Role role, FlankSide side, double x, double z) {
        return new Assignment(id, role, null, side, x, z);
    }

    private static Assignment find(Roles r, int id) {
        for (Assignment a : r.assignments) if (a.unitId == id) return a;
        throw new AssertionError("no assignment for " + id);
    }

    private static void leapfrog() {
        GroupSnapshot g = new GroupSnapshot(new int[] {1, 2}, new double[] {0, 0}, new double[] {0, 40});
        // Unit 1 bounds to (0,0) and is there; unit 2 overwatches at (0,40).
        Roles previous = new Roles(new Assignment[] {
                a(1, Role.MANEUVER, null, 0, 0), a(2, Role.OVERWATCH, null, 0, 40)});
        Roles fresh = new Roles(new Assignment[] {
                a(1, Role.OVERWATCH, null, 0, 10), a(2, Role.MANEUVER, null, 0, -20)});
        Roles swapped = TacticPostProcess.leapfrog(PlayId.BOUNDING_OVERWATCH_ADVANCE, previous, previous, fresh, g, 8);
        assert find(swapped, 1).role == Role.OVERWATCH && find(swapped, 1).destZ == 10 : "mover becomes overwatch";
        assert find(swapped, 2).role == Role.MANEUVER && find(swapped, 2).destZ == -20 : "overwatch takes the bound";

        GroupSnapshot enRoute = new GroupSnapshot(new int[] {1, 2}, new double[] {0, 0}, new double[] {30, 40});
        assert TacticPostProcess.leapfrog(PlayId.BOUNDING_OVERWATCH_ADVANCE, previous, previous, fresh, enRoute, 8)
                == previous : "mover not on its point yet: no swap";
        assert TacticPostProcess.leapfrog(PlayId.FRONTAL_FIX_AND_FLANK, previous, previous, fresh, g, 8)
                == previous : "other plays are untouched";

        Roles withdraw = new Roles(new Assignment[] {
                a(1, Role.WITHDRAW, null, 0, 0), a(2, Role.OVERWATCH, null, 0, 40)});
        Roles wFresh = new Roles(new Assignment[] {
                a(1, Role.OVERWATCH, null, 0, 10), a(2, Role.WITHDRAW, null, 0, 90)});
        Roles wSwapped = TacticPostProcess.leapfrog(PlayId.FIGHTING_WITHDRAWAL, withdraw, withdraw, wFresh, g, 8);
        assert find(wSwapped, 2).role == Role.WITHDRAW && find(wSwapped, 2).destZ == 90 : "withdrawal leapfrogs too";
    }

    /**
     * The fallback template (one centred line, 16 apart, facing the enemy) must reproduce the old firing line
     * exactly: same spread, same lateral order, nothing else touched.
     */
    private static void formationSlots() {
        BattleField bf = new BattleField();
        bf.clear();
        bf.axisX = 0;
        bf.axisZ = 1; // enemy to the north; left = (-1, 0)
        GroupSnapshot g = new GroupSnapshot(new int[] {1, 2, 3, 4},
                new double[] {10, -10, 0, 50}, new double[] {0, 0, 0, 0});
        Roles roles = new Roles(new Assignment[] {
                a(1, Role.BASE_OF_FIRE, null, 0, 20), a(2, Role.BASE_OF_FIRE, null, 0, 20),
                a(3, Role.BASE_OF_FIRE, null, 0, 20), a(4, Role.MANEUVER, FlankSide.LEFT, 99, 99)});
        FormationTemplates fb = FormationTemplates.fallback();
        Roles line = PlayFormations.lay(PlayId.HOLD_DEFEND, bf, roles, null, g, fb, 1.0);
        assert find(line, 1).destX == 16 && find(line, 3).destX == 0 && find(line, 2).destX == -16
                : "spread across the axis in lateral order";
        assert find(line, 1).destZ == 20 && find(line, 2).destZ == 20 : "on the line through the point";
        assert find(line, 1).facingX == 0 && find(line, 1).facingZ == -1 : "the line faces the enemy";
        assert find(line, 4).destX == 99 && find(line, 4).destZ == 99 : "a lone flanker stays on its mark";
        assert find(line, 1).slot >= 0 && find(line, 4).slot == 0 : "every laid member has a slot";

        Roles single = new Roles(new Assignment[] {a(1, Role.BASE_OF_FIRE, null, 0, 20)});
        Assignment one = PlayFormations.lay(PlayId.HOLD_DEFEND, bf, single, null, g, fb, 1.0).assignments[0];
        assert one.destX == 0 && one.destZ == 20 : "one support tank: nothing to spread";

        // Stickiness: re-laid with everyone nudged toward a neighbour's slot, nobody changes slot.
        GroupSnapshot nudged = new GroupSnapshot(new int[] {1, 2, 3, 4},
                new double[] {7, -2, -6, 50}, new double[] {0, 0, 0, 0});
        Roles again = PlayFormations.lay(PlayId.HOLD_DEFEND, bf, roles, line, nudged, fb, 1.0);
        for (int id = 1; id <= 3; id++) {
            assert find(again, id).slot == find(line, id).slot : "member " + id + " kept its slot";
        }

        // Scale stretches the formation, not its shape.
        Roles wide = PlayFormations.lay(PlayId.HOLD_DEFEND, bf, roles, null, g, fb, 2.0);
        assert find(wide, 1).destX == 32 && find(wide, 2).destX == -32 : "tactical scale applies to spacing";
    }

    /** The shipped table: distinct points for every member, mirrored wings, and arrival tighter than spacing. */
    private static void shippedFormations() {
        FormationTemplates shipped = shippedTemplates();
        BattleField bf = new BattleField();
        bf.clear();
        bf.populated = true;
        bf.axisX = 0;
        bf.axisZ = 1;
        bf.enemyCentroidX = 0;
        bf.enemyCentroidZ = -100;
        int n = 6;
        int[] ids = new int[n];
        double[] xs = new double[n];
        double[] zs = new double[n];
        Assignment[] hold = new Assignment[n];
        for (int i = 0; i < n; i++) {
            ids[i] = i + 1;
            xs[i] = i * 3;
            zs[i] = 0;
            hold[i] = a(i + 1, Role.HOLD, null, 0, -20);
        }
        GroupSnapshot g = new GroupSnapshot(ids, xs, zs);
        Roles laid = PlayFormations.lay(PlayId.HOLD_DEFEND, bf, new Roles(hold), null, g, shipped, 1.0);
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Assignment p = laid.assignments[i];
                Assignment q = laid.assignments[j];
                assert Math.hypot(p.destX - q.destX, p.destZ - q.destZ) >= 15.999 : "hold members share no point";
            }
        }

        // Wings on mirrored marks (left = -x here) lay mirror-image formations.
        Assignment[] wings = new Assignment[4];
        wings[0] = a(1, Role.MANEUVER, FlankSide.LEFT, -56, -100);
        wings[1] = a(2, Role.MANEUVER, FlankSide.LEFT, -56, -100);
        wings[2] = a(3, Role.MANEUVER, FlankSide.RIGHT, 56, -100);
        wings[3] = a(4, Role.MANEUVER, FlankSide.RIGHT, 56, -100);
        GroupSnapshot wg = new GroupSnapshot(new int[] {1, 2, 3, 4},
                new double[] {-10, -20, 10, 20}, new double[] {0, 0, 0, 0});
        Roles w = PlayFormations.lay(PlayId.DOUBLE_ENVELOPMENT, bf, new Roles(wings), null, wg, shipped, 1.0);
        double[][] left = {{find(w, 1).destX, find(w, 1).destZ}, {find(w, 2).destX, find(w, 2).destZ}};
        double[][] right = {{find(w, 3).destX, find(w, 3).destZ}, {find(w, 4).destX, find(w, 4).destZ}};
        for (double[] l : left) {
            boolean mirrored = false;
            for (double[] r : right) {
                mirrored |= Math.abs(l[0] + r[0]) < 1e-9 && Math.abs(l[1] - r[1]) < 1e-9;
            }
            assert mirrored : "left wing slot " + l[0] + "," + l[1] + " has no mirror on the right";
        }

        // A crew within ARRIVE of its slot must not also be within ARRIVE of a neighbour's.
        assert 2.0 * TaskedDestination.ARRIVE <= shipped.minSpacing()
                : "ARRIVE " + TaskedDestination.ARRIVE + " vs spacing " + shipped.minSpacing();

        // Every role a play can deal has its own row — nothing silently lands on the fallback line.
        for (Role role : Role.values()) {
            if (role == Role.IDLE_HOLD || role == Role.IDLE_TRAVEL) continue;
            assert shipped.defines(PlayFormations.roleKey(role, null)) : role;
        }
        assert shipped.defines(PlayFormations.roleKey(Role.MANEUVER, FlankSide.LEFT));
        assert shipped.defines(PlayFormations.roleKey(Role.MANEUVER, FlankSide.RIGHT));
    }

    static FormationTemplates shippedTemplates() {
        try (var in = TacticsSelfCheck.class.getResourceAsStream(
                "/data/tacz_sewv/sewv/formations/formations.json")) {
            if (in == null) throw new AssertionError("shipped formations.json is not on the classpath");
            return FormationTemplates.parse(Map.of(new ResourceLocation("tacz_sewv", "formations"),
                    JsonParser.parseReader(new java.io.InputStreamReader(in))));
        } catch (java.io.IOException e) {
            throw new AssertionError("shipped formations.json unreadable", e);
        }
    }

    private static void commanderBack() {
        GroupSnapshot g = new GroupSnapshot(new int[] {1, 2, 3},
                new double[] {0, 5, 50}, new double[] {0, 0, 0});
        Roles roles = new Roles(new Assignment[] {
                a(1, Role.MANEUVER, FlankSide.RIGHT, 100, 0), a(2, Role.BASE_OF_FIRE, null, 0, 20),
                a(3, Role.OVERWATCH, null, 0, 30)});
        Roles back = TacticPostProcess.commanderBack(roles, g, 1);
        assert find(back, 1).role == Role.BASE_OF_FIRE && find(back, 1).flankSide == null
                : "commander takes the nearest supporting role";
        assert find(back, 2).role == Role.MANEUVER && find(back, 2).flankSide == FlankSide.RIGHT
                && find(back, 2).destX == 100 : "that member takes the flank";
        assert TacticPostProcess.commanderBack(back, g, 1) == back : "already back: unchanged";
        Roles slotted = new Roles(new Assignment[] {
                new Assignment(1, Role.MANEUVER, null, FlankSide.RIGHT, 100, 0, 2, 1, 0),
                new Assignment(2, Role.BASE_OF_FIRE, null, null, 0, 20, 0, 0, -1)});
        Roles swapped = TacticPostProcess.commanderBack(slotted, g, 1);
        assert find(swapped, 1).slot == 0 && find(swapped, 1).facingZ == -1 : "commander takes the slot with the role";
        assert find(swapped, 2).slot == 2 && find(swapped, 2).facingX == 1 : "and hands over its own";
        assert TacticPostProcess.commanderBack(roles, g, 3) == roles : "commander in a holding role: unchanged";
    }

    private static void coverPoints() {
        Roles roles = new Roles(new Assignment[] {
                a(1, Role.BASE_OF_FIRE, null, 0, 0), a(2, Role.MANEUVER, FlankSide.LEFT, 0, 0),
                a(3, Role.WITHDRAW, null, 100, 100)});
        // Cover only east of x=12 near the first point; the withdraw area is open everywhere.
        Roles snapped = TacticPostProcess.coverPoints(roles, 8, 16,
                (x, z) -> (x > 12 && Math.abs(z) < 1 && x < 50) ? 0.0 : 1.0);
        assert find(snapped, 1).destX == 16 && Math.abs(find(snapped, 1).destZ) < 1e-9 : "BoF moves into cover";
        assert find(snapped, 2).destX == 0 : "maneuver is never snapped";
        assert find(snapped, 3).destX == 100 && find(snapped, 3).destZ == 100 : "no cover nearby: point kept";
        Roles none = TacticPostProcess.coverPoints(roles, 8, 16, (x, z) -> 0.5);
        assert none == roles : "uniform exposure: unchanged";

        // Two neighbouring slots, one scrap of cover between them: only one may take it.
        Roles pair = new Roles(new Assignment[] {
                new Assignment(1, Role.BASE_OF_FIRE, null, null, 0, 0, 0, 0, -1),
                new Assignment(2, Role.BASE_OF_FIRE, null, null, 16, 0, 1, 0, -1)});
        Roles split = TacticPostProcess.coverPoints(pair, 8, 16, 8,
                (x, z) -> Math.abs(x - 8) < 1e-9 && Math.abs(z) < 1e-9 ? 0.0 : 1.0);
        assert find(split, 1).destX == 8 : "first slot takes the cover";
        assert find(split, 2).destX == 16 : "its neighbour may not fold onto it";
        assert find(split, 1).slot == 0 && find(split, 1).facingZ == -1 : "slot and facing survive the snap";

        // The 00:12:54 playtest bug: cover right beside the NEXT slot (not yet searched) must not be taken by the
        // first — it used to be, leaving two base-of-fire hulls four blocks apart.
        Roles line = new Roles(new Assignment[] {
                new Assignment(1, Role.BASE_OF_FIRE, null, null, 0, 0, 0, 0, -1),
                new Assignment(2, Role.BASE_OF_FIRE, null, null, 32, 0, 1, 0, -1)});
        Roles kept = TacticPostProcess.coverPoints(line, 16, 32, 16,
                (x, z) -> Math.abs(x - 32) < 1e-9 && Math.abs(z) < 1e-9 ? 0.0 : 1.0);
        assert find(kept, 1).destX == 0 && find(kept, 2).destX == 32
                : "first slot may not snap onto its neighbour's ground";
        Roles three = new Roles(new Assignment[] {
                new Assignment(1, Role.BASE_OF_FIRE, null, null, 0, 0, 0, 0, -1),
                new Assignment(2, Role.BASE_OF_FIRE, null, null, 32, 0, 1, 0, -1),
                new Assignment(3, Role.BASE_OF_FIRE, null, null, 64, 0, 2, 0, -1)});
        java.util.Random rnd = new java.util.Random(7);
        for (int trial = 0; trial < 200; trial++) {
            double[] good = {rnd.nextDouble() * 96 - 16, rnd.nextDouble() * 32 - 16};
            Roles r = TacticPostProcess.coverPoints(three, 16, 32, 16,
                    (x, z) -> Math.hypot(x - good[0], z - good[1]) / 100.0);
            for (int i = 0; i < 3; i++) {
                for (int j = i + 1; j < 3; j++) {
                    Assignment p = r.assignments[i];
                    Assignment q = r.assignments[j];
                    assert Math.hypot(p.destX - q.destX, p.destZ - q.destZ) >= 16 - 1e-9
                            : "slots " + i + "/" + j + " folded together (trial " + trial + ")";
                }
            }
        }
    }

    private TacticsSelfCheck() {}
}
