package com.neoalive.tacz_sewv.entity.ai.command;

import javax.annotation.Nullable;

import com.neoalive.tacz_sewv.entity.ai.utility.Action;
import com.neoalive.tacz_sewv.entity.ai.utility.TacticalScale;

/**
 * Where a play's role sends a crew, for the action the crew's own scorer chose.
 *
 * <p>The point is only honoured when the chosen action IS the role's action: the scorer keeps authority, so a
 * hurt flanker that chose RETREAT is not dragged to the flank mark. Once reached, the action's own geometry
 * (orbit, standoff, retreat, stop) takes over — this is geometry for an already-chosen action, not a decision.
 * Pure: headless-testable.
 */
public final class TaskedDestination {

    /** Horizontal distance at which a crew counts as on its point (× {@link TacticalScale}). */
    static final double ARRIVE = 8.0;
    /** A reached point that has since moved this far is a new bound: drive again (× {@link TacticalScale}). */
    static final double RELATCH = 24.0;
    /** Blocks closer the hull must get within {@link #STALL_TICKS} to count as still making for the point. */
    static final double PROGRESS_STEP = 4.0;
    /** Game ticks without that progress before a role point is given up as unreachable (10 s). */
    static final long STALL_TICKS = 200;

    private TaskedDestination() {}

    /** {@code {x, z}} of the role's point if {@code plan} is that role's action, else null. */
    @Nullable
    public static double[] pointFor(@Nullable CrewAssignment.Snapshot a, Action plan) {
        if (a == null || !a.hasDest()) return null;
        boolean matches = switch (a.role()) {
            case MANEUVER -> a.flankSide() == null
                    ? plan == Action.ADVANCE
                    : plan == (a.flankSide() == Assignment.FlankSide.LEFT ? Action.FLANK_LEFT : Action.FLANK_RIGHT);
            case WITHDRAW -> plan == Action.RETREAT;
            case OVERWATCH, HOLD, RESERVE -> plan == Action.HOLD;
            case BASE_OF_FIRE -> plan == Action.ATTACK;
            case IDLE_HOLD, IDLE_TRAVEL -> false;
        };
        return matches ? new double[] {a.destX(), a.destZ()} : null;
    }

    /**
     * Per-crew "already there" memory. Without it a crew reaching its flank mark would orbit off it, fall outside
     * {@link #ARRIVE}, and drive back — forever.
     */
    public static final class Latch {
        @Nullable private Assignment.Role role;
        @Nullable private Assignment.FlankSide side;
        private double reachedX = Double.NaN;
        private double reachedZ = Double.NaN;
        // Progress toward the point, for stalled(): the closest the hull has been, when, and where the point was.
        private double bestDist = Double.MAX_VALUE;
        private long bestAt = Long.MIN_VALUE;
        private double bestPx = Double.NaN;
        private double bestPz = Double.NaN;

        /** Should the crew at {@code (hx, hz)} still drive to {@code (px, pz)}? */
        public boolean shouldDrive(Assignment.Role role, @Nullable Assignment.FlankSide side,
                                   double px, double pz, double hx, double hz) {
            if (role != this.role || side != this.side) {
                this.role = role;
                this.side = side;
                this.reachedX = Double.NaN;
                forgetProgress();
            }
            double relatch = TacticalScale.of(RELATCH);
            double arrive = TacticalScale.of(ARRIVE);
            if (!Double.isNaN(this.reachedX)) {
                if (distSq(px, pz, this.reachedX, this.reachedZ) <= relatch * relatch) return false;
                this.reachedX = Double.NaN;
            }
            if (distSq(px, pz, hx, hz) <= arrive * arrive) {
                this.reachedX = px;
                this.reachedZ = pz;
                return false;
            }
            return true;
        }

        /**
         * Has the crew stopped closing on the point? True once it has gone {@link #STALL_TICKS} without getting
         * {@link #PROGRESS_STEP} closer, and the point is then latched as if reached: the action's own geometry takes
         * over until the coordinator moves the point past {@link #RELATCH}. Nothing checks a role point for
         * reachability, so a point across water or out of a terrain pocket otherwise parked the hull at the shore,
         * repathing a one-node stub forever. Measured by distance, not by the path, so it covers every way of
         * failing to arrive. A point that moves more than {@link #RELATCH} restarts the clock.
         */
        public boolean stalled(double px, double pz, double hx, double hz, long now) {
            double d = Math.sqrt(distSq(px, pz, hx, hz));
            double relatch = TacticalScale.of(RELATCH);
            boolean pointMoved = !Double.isNaN(this.bestPx)
                    && distSq(px, pz, this.bestPx, this.bestPz) > relatch * relatch;
            if (this.bestAt == Long.MIN_VALUE || pointMoved || d < this.bestDist - PROGRESS_STEP) {
                this.bestDist = d;
                this.bestAt = now;
                this.bestPx = px;
                this.bestPz = pz;
                return false;
            }
            if (now - this.bestAt < STALL_TICKS) return false;
            this.reachedX = px;
            this.reachedZ = pz;
            forgetProgress();
            return true;
        }

        private void forgetProgress() {
            this.bestDist = Double.MAX_VALUE;
            this.bestAt = Long.MIN_VALUE;
            this.bestPx = Double.NaN;
            this.bestPz = Double.NaN;
        }

        public void reset() {
            this.role = null;
            this.side = null;
            this.reachedX = Double.NaN;
            forgetProgress();
        }

        private static double distSq(double ax, double az, double bx, double bz) {
            double dx = ax - bx;
            double dz = az - bz;
            return dx * dx + dz * dz;
        }
    }
}
