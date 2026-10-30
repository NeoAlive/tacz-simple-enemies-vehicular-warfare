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
        stall();
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

    /** A point the hull cannot get closer to is given up and latched as reached, and only for that point. */
    private static void stall() {
        TaskedDestination.Latch l = new TaskedDestination.Latch();
        long t = 1000;
        assert l.shouldDrive(Role.MANEUVER, null, 200, 0, 0, 0);
        assert !l.stalled(200, 0, 0, 0, t) : "first sample starts the clock";
        assert !l.stalled(200, 0, 2, 0, t + TaskedDestination.STALL_TICKS - 1) : "creeping, not yet out of time";
        assert l.stalled(200, 0, 3, 0, t + TaskedDestination.STALL_TICKS) : "no real progress in time: give up";
        assert !l.shouldDrive(Role.MANEUVER, null, 200, 0, 3, 0) : "given up = latched as reached";

        // Steady progress never stalls.
        TaskedDestination.Latch p = new TaskedDestination.Latch();
        for (int i = 0; i <= 40; i++) {
            assert !p.stalled(200, 0, i * 5.0, 0, t + i * 20L) : "closing 5 blocks a second, i=" + i;
        }

        // A point that keeps moving away is a new point, not a stall.
        TaskedDestination.Latch m = new TaskedDestination.Latch();
        assert !m.stalled(200, 0, 0, 0, t);
        assert !m.stalled(260, 0, 0, 0, t + TaskedDestination.STALL_TICKS) : "point moved > RELATCH: clock restarts";

        // The abandonment is released when the coordinator moves the point.
        assert l.shouldDrive(Role.MANEUVER, null, 260, 0, 3, 0) : "moved point: drive again";
    }

    private TaskedDestinationSelfCheck() {}
}
