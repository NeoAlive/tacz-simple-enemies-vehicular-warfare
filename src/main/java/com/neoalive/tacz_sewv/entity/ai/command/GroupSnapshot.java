package com.neoalive.tacz_sewv.entity.ai.command;

/**
 * Plain group view for pure play cores — driver ids + positions, no world types.
 */
public final class GroupSnapshot {

    public final int[] memberIds;
    public final double[] x;
    public final double[] z;

    public GroupSnapshot(int[] memberIds, double[] x, double[] z) {
        if (memberIds.length != x.length || x.length != z.length) {
            throw new IllegalArgumentException("memberIds/x/z length mismatch");
        }
        this.memberIds = memberIds;
        this.x = x;
        this.z = z;
    }

    public int size() {
        return this.memberIds.length;
    }
}
