package com.neoalive.tacz_sewv.entity.ai.command;

/**
 * One group after a pure {@link Grouping#groupAssignments} pass — sticky id preserved when the
 * group survived, new id when freshly formed.
 */
public final class AssignedGroup {

    public final int groupId;
    public final int faction;
    public final int[] memberIds;
    public final double centroidX;
    public final double centroidZ;

    public AssignedGroup(int groupId, int faction, int[] memberIds, double centroidX, double centroidZ) {
        this.groupId = groupId;
        this.faction = faction;
        this.memberIds = memberIds;
        this.centroidX = centroidX;
        this.centroidZ = centroidZ;
    }
}
