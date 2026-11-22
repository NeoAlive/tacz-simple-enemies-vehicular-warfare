package com.neoalive.tacz_sewv.entity.ai.command;

import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;

/**
 * Plain group view for pure play cores — driver ids + positions, no world types.
 */
public final class GroupSnapshot {

    public final int[] memberIds;
    public final double[] x;
    public final double[] z;
    /** The group's doctrine: its commander's (for a PMC group, the owner's ledger). Neutral when unknown. */
    public final Doctrine doctrine;

    public GroupSnapshot(int[] memberIds, double[] x, double[] z) {
        this(memberIds, x, z, Doctrine.NEUTRAL);
    }

    public GroupSnapshot(int[] memberIds, double[] x, double[] z, Doctrine doctrine) {
        if (memberIds.length != x.length || x.length != z.length) {
            throw new IllegalArgumentException("memberIds/x/z length mismatch");
        }
        this.memberIds = memberIds;
        this.x = x;
        this.z = z;
        this.doctrine = doctrine == null ? Doctrine.NEUTRAL : doctrine;
    }

    public int size() {
        return this.memberIds.length;
    }
}
