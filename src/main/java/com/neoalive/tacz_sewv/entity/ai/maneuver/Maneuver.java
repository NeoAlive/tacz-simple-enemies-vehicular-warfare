package com.neoalive.tacz_sewv.entity.ai.maneuver;

/**
 * A planned full-lock multi-point turn: three segments, executed in order.
 *
 * <ul>
 * <li>{@code dirs[i]} — +1 forward, -1 astern. Never mirrored.</li>
 * <li>{@code left[i]} — the side the BOW swings (not the key: astern, a left swing is the right
 *     key, because SBW flips yaw sense with power sign). <b>A mirror flips {@code left[]} and
 *     nothing else.</b></li>
 * <li>{@code sweep[i]} — net yaw change of the segment, a magnitude in [0, pi]. The executor ends
 *     the segment when the measured yaw change in the {@code left[i]} direction reaches it.</li>
 * </ul>
 * {@code score} and {@code ticks} are the planner's prediction, for logging.
 */
public record Maneuver(int[] dirs, boolean[] left, double[] sweep, double score, double ticks) {

    public Maneuver mirrored() {
        boolean[] flipped = new boolean[this.left.length];
        for (int i = 0; i < flipped.length; i++) flipped[i] = !this.left[i];
        return new Maneuver(this.dirs.clone(), flipped, this.sweep.clone(), this.score, this.ticks);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < this.dirs.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(this.dirs[i] > 0 ? 'F' : 'R').append(this.left[i] ? 'L' : 'R')
                    .append(Math.round(StrictMath.toDegrees(this.sweep[i])));
        }
        return sb.append(" score=").append(this.score).append(" ticks=").append(this.ticks).toString();
    }
}
