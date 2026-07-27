package com.neoalive.tacz_sewv.entity.ai.command;

/**
 * Sticky group snapshot fed into {@link Grouping#groupAssignments} so membership can use the
 * join/leave hysteresis band against last scan's centroid.
 */
public final class ExistingGroup {

    public final int groupId;
    public final int faction;
    public final int[] memberIds;
    public final double centroidX;
    public final double centroidZ;

    public ExistingGroup(int groupId, int faction, int[] memberIds, double centroidX, double centroidZ) {
        this.groupId = groupId;
        this.faction = faction;
        this.memberIds = memberIds;
        this.centroidX = centroidX;
        this.centroidZ = centroidZ;
    }
}
