package com.neoalive.tacz_sewv.entity.ai.command;

import com.neoalive.tacz_sewv.entity.ai.command.Assignment.FlankSide;
import com.neoalive.tacz_sewv.entity.ai.command.Assignment.Role;

/**
 * Headless check of the group tactic post-processes: leapfrog, firing line, commander back, cover points.
 * Run via {@code ./gradlew selfCheckTactics}.
 */
public final class TacticsSelfCheck {

    public static void main(String[] args) {
        boolean on = false;
        assert on = true;
        if (!on) throw new IllegalStateException("run with -ea");
        leapfrog();
        firingLine();
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

    private static void firingLine() {
        BattleField bf = new BattleField();
        bf.clear();
        bf.axisX = 0;
        bf.axisZ = 1; // left = (-1, 0)
        GroupSnapshot g = new GroupSnapshot(new int[] {1, 2, 3, 4},
                new double[] {10, -10, 0, 50}, new double[] {0, 0, 0, 0});
        Roles roles = new Roles(new Assignment[] {
                a(1, Role.BASE_OF_FIRE, null, 0, 20), a(2, Role.BASE_OF_FIRE, null, 0, 20),
                a(3, Role.BASE_OF_FIRE, null, 0, 20), a(4, Role.MANEUVER, FlankSide.LEFT, 99, 99)});
        Roles line = TacticPostProcess.firingLine(bf, roles, g, 16);
        // Ascending along left (-x): unit 1 (x=10) first → rightmost slot.
        assert find(line, 1).destX == 16 && find(line, 3).destX == 0 && find(line, 2).destX == -16
                : "spread across the axis in lateral order";
        assert find(line, 1).destZ == 20 && find(line, 2).destZ == 20 : "on the line through the point";
        assert find(line, 4).destX == 99 : "maneuver untouched";
        Roles single = new Roles(new Assignment[] {a(1, Role.BASE_OF_FIRE, null, 0, 20)});
        assert TacticPostProcess.firingLine(bf, single, g, 16) == single : "one support tank: nothing to spread";
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
    }

    private TacticsSelfCheck() {}
}
