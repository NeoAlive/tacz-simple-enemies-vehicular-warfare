package com.neoalive.tacz_sewv.entity.ai.command;

/**
 * Snapshot of grouping radii for one pure {@link Grouping#groupAssignments} call.
 *
 * <p>{@code leaveRadius} must be {@code > joinRadius} or membership flickers at the edge.
 * {@code maxDiameter/2} should be {@code >= leaveRadius} so the leave band fits inside the
 * diameter ball — otherwise diameter clips the hysteresis away. {@code maxDiameter} is the hard
 * ball diameter (members must stay within half of it of the centroid) — the anti-smear gate that
 * stops single-linkage chaining.
 */
public final class GroupParams {

    public final double joinRadius;
    public final double leaveRadius;
    public final double maxDiameter;
    public final int minSize;

    public GroupParams(double joinRadius, double leaveRadius, double maxDiameter, int minSize) {
        this.joinRadius = joinRadius;
        this.leaveRadius = leaveRadius;
        this.maxDiameter = maxDiameter;
        this.minSize = Math.max(2, minSize);
    }

    /** Half-diameter: maximum allowed distance from centroid to any member. */
    public double maxRadius() {
        return this.maxDiameter * 0.5;
    }
}
