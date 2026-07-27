package com.neoalive.tacz_sewv.entity.ai.command;

import java.util.Arrays;

/**
 * Sticky battle group — identity persists across scans while a quorum of members stay co-located.
 *
 * <p>Stage 1 only tracks membership and centroid. Commander / play / influence fields land in
 * later stages on this same object.
 */
public final class BattleGroup {

    private final int groupId;
    private final int faction;
    private int[] memberIds;
    private double centroidX;
    private double centroidZ;

    public BattleGroup(int groupId, int faction, int[] memberIds, double centroidX, double centroidZ) {
        this.groupId = groupId;
        this.faction = faction;
        this.memberIds = memberIds.clone();
        this.centroidX = centroidX;
        this.centroidZ = centroidZ;
    }

    public int groupId() {
        return this.groupId;
    }

    public int faction() {
        return this.faction;
    }

    public int[] memberIds() {
        return this.memberIds.clone();
    }

    public boolean contains(int unitId) {
        for (int id : this.memberIds) {
            if (id == unitId) return true;
        }
        return false;
    }

    public int size() {
        return this.memberIds.length;
    }

    public double centroidX() {
        return this.centroidX;
    }

    public double centroidZ() {
        return this.centroidZ;
    }

    void apply(AssignedGroup assigned) {
        this.memberIds = assigned.memberIds.clone();
        this.centroidX = assigned.centroidX;
        this.centroidZ = assigned.centroidZ;
    }

    ExistingGroup toExisting() {
        return new ExistingGroup(this.groupId, this.faction, this.memberIds.clone(),
                this.centroidX, this.centroidZ);
    }

    @Override
    public String toString() {
        return "BattleGroup{id=" + this.groupId + " faction=" + this.faction
                + " members=" + Arrays.toString(this.memberIds)
                + " c=(" + this.centroidX + "," + this.centroidZ + ")}";
    }
}
