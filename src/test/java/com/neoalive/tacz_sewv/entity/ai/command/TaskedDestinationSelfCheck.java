package com.neoalive.tacz_sewv.entity.ai.command;

import com.neoalive.tacz_sewv.entity.ai.command.Assignment.FlankSide;
import com.neoalive.tacz_sewv.entity.ai.command.Assignment.Role;
import com.neoalive.tacz_sewv.entity.ai.utility.Action;

/**
 * Headless check of the role → action → point table and the arrival latch.
 * Run via {@code ./gradlew selfCheckTaskedDestination}.
 */
public final class TaskedDestinationSelfCheck {

    public static void main(String[] args) {
        boolean on = false;
        assert on = true;
        if (!on) throw new IllegalStateException("run with -ea");
        table();
        latch();
        System.out.println("TaskedDestinationSelfCheck OK");
    }

    private static CrewAssignment.Snapshot snap(Role role, FlankSide side) {
        return new CrewAssignment.Snapshot(role, side, null, 10.0, 20.0);
    }

    private static void table() {
        assert TaskedDestination.pointFor(snap(Role.MANEUVER, FlankSide.LEFT), Action.FLANK_LEFT) != null;
        assert TaskedDestination.pointFor(snap(Role.MANEUVER, FlankSide.LEFT), Action.FLANK_RIGHT) == null
                : "wrong-side flank must not be dragged to the other wing's mark";
        assert TaskedDestination.pointFor(snap(Role.MANEUVER, FlankSide.RIGHT), Action.FLANK_RIGHT) != null;
        assert TaskedDestination.pointFor(snap(Role.MANEUVER, null), Action.ADVANCE) != null;
        assert TaskedDestination.pointFor(snap(Role.MANEUVER, null), Action.FLANK_LEFT) == null;
        assert TaskedDestination.pointFor(snap(Role.WITHDRAW, null), Action.RETREAT) != null;
        assert TaskedDestination.pointFor(snap(Role.WITHDRAW, null), Action.ATTACK) == null;
        for (Role r : new Role[] {Role.OVERWATCH, Role.HOLD, Role.RESERVE}) {
            assert TaskedDestination.pointFor(snap(r, null), Action.HOLD) != null : r;
        }
        assert TaskedDestination.pointFor(snap(Role.BASE_OF_FIRE, null), Action.ATTACK) != null;
        assert TaskedDestination.pointFor(snap(Role.MANEUVER, FlankSide.LEFT), Action.RETREAT) == null
                : "a hurt flanker that chose to retreat is not pulled to the mark";
        assert TaskedDestination.pointFor(snap(Role.IDLE_HOLD, null), Action.HOLD) == null;
        assert TaskedDestination.pointFor(null, Action.ATTACK) == null;
        assert TaskedDestination.pointFor(new CrewAssignment.Snapshot(Role.WITHDRAW, null, null, Double.NaN, Double.NaN),
                Action.RETREAT) == null : "no destination published";
        double[] p = TaskedDestination.pointFor(snap(Role.BASE_OF_FIRE, null), Action.ATTACK);
        assert p[0] == 10.0 && p[1] == 20.0;
    }

    private static void latch() {
        TaskedDestination.Latch l = new TaskedDestination.Latch();
        assert l.shouldDrive(Role.MANEUVER, FlankSide.LEFT, 100, 0, 0, 0) : "far: drive";
        assert !l.shouldDrive(Role.MANEUVER, FlankSide.LEFT, 100, 0, 95, 0) : "inside ARRIVE: reached";
        assert !l.shouldDrive(Role.MANEUVER, FlankSide.LEFT, 100, 0, 60, 0) : "stays reached after orbiting off";
        assert !l.shouldDrive(Role.MANEUVER, FlankSide.LEFT, 110, 0, 60, 0) : "point drifted < RELATCH: still reached";
        assert l.shouldDrive(Role.MANEUVER, FlankSide.LEFT, 130, 0, 60, 0) : "point moved > RELATCH: new bound";
        assert !l.shouldDrive(Role.MANEUVER, FlankSide.LEFT, 130, 0, 125, 0);
        assert l.shouldDrive(Role.MANEUVER, FlankSide.RIGHT, 130, 0, 60, 0) : "side change forgets arrival";
        assert !l.shouldDrive(Role.MANEUVER, FlankSide.RIGHT, 130, 0, 128, 0);
        l.reset();
        assert l.shouldDrive(Role.MANEUVER, FlankSide.RIGHT, 130, 0, 60, 0) : "reset forgets arrival";
    }

    private TaskedDestinationSelfCheck() {}
}
