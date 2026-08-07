package com.neoalive.tacz_sewv.entity.ai.command;

import java.util.Arrays;
import java.util.UUID;

import javax.annotation.Nullable;

/**
 * Sticky battle group — identity persists across scans while a quorum of members stay co-located.
 */
public final class BattleGroup {

    private final int groupId;
    private final int faction;
    private int[] memberIds;
    private double centroidX;
    private double centroidZ;

    /** Network id of the elected commander, or {@link Integer#MIN_VALUE} if none yet. */
    private int commanderId = Integer.MIN_VALUE;

    /**
     * TODO(command-player-designation): TDT / Xaero menu writes the designated unit's UUID here.
     * When set and that unit is alive and in-group, election uses it as commander.
     */
    @Nullable
    private UUID playerDesignatedCommander;

    /** Reused across command-cadence rebuilds — never allocated fresh per scan. */
    private final InfluenceMap influenceMap = new InfluenceMap();
    private final BattleField battleField = new BattleField();
    private double lastInfluenceCentroidX = Double.NaN;
    private double lastInfluenceCentroidZ = Double.NaN;
    private int lastMemberFingerprint;
    private boolean hasInfluenceStamp;

    @Nullable
    private PlayId currentPlay;
    /** Server tick when {@link #currentPlay} was committed; {@link Long#MIN_VALUE} = none. */
    private long playStartedTick = Long.MIN_VALUE;
    @Nullable
    private Roles currentRoles;

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

    /** Allocentric influence grid — reused; rebuild only from the coordinator. */
    public InfluenceMap influenceMap() {
        return this.influenceMap;
    }

    /** True when the group changed enough that its cached influence field is no longer reusable. */
    boolean needsInfluenceRebuild(double movementThreshold) {
        int fingerprint = memberFingerprint();
        if (!this.hasInfluenceStamp || fingerprint != this.lastMemberFingerprint) return true;
        double dx = this.centroidX - this.lastInfluenceCentroidX;
        double dz = this.centroidZ - this.lastInfluenceCentroidZ;
        return dx * dx + dz * dz > movementThreshold * movementThreshold;
    }

    /** Stamp only after a successful influence rebuild. */
    void markInfluenceRebuilt() {
        this.lastInfluenceCentroidX = this.centroidX;
        this.lastInfluenceCentroidZ = this.centroidZ;
        this.lastMemberFingerprint = memberFingerprint();
        this.hasInfluenceStamp = true;
    }

    private int memberFingerprint() {
        int[] sorted = this.memberIds.clone();
        Arrays.sort(sorted);
        return Arrays.hashCode(sorted);
    }

    /** Derived battle facts — gathers, never decides. Cleared when the group dissolves. */
    public BattleField battleField() {
        return this.battleField;
    }

    /**
     * Centroid used for commander fitness. Prefers a populated {@link BattleField} friendly
     * centroid (Stage 3); falls back to the sticky group mean (Stage 1/2) when the map has
     * not been built yet this scan.
     */
    public double fitnessCentroidX() {
        return this.battleField.populated ? this.battleField.friendlyCentroidX : this.centroidX;
    }

    public double fitnessCentroidZ() {
        return this.battleField.populated ? this.battleField.friendlyCentroidZ : this.centroidZ;
    }

    @Nullable
    public PlayId currentPlay() {
        return this.currentPlay;
    }

    public long playStartedTick() {
        return this.playStartedTick;
    }

    @Nullable
    public Roles currentRoles() {
        return this.currentRoles;
    }

    void commitPlay(PlayId play, Roles roles, long nowTick) {
        this.currentPlay = play;
        this.currentRoles = roles;
        this.playStartedTick = nowTick;
    }

    void clearPlay() {
        this.currentPlay = null;
        this.currentRoles = null;
        this.playStartedTick = Long.MIN_VALUE;
        this.hasInfluenceStamp = false;
    }

    /** Elected commander network id, or empty if election has deferred / never run. */
    public boolean hasCommander() {
        return this.commanderId != Integer.MIN_VALUE;
    }

    public int commanderId() {
        return this.commanderId;
    }

    void setCommanderId(int commanderId) {
        this.commanderId = commanderId;
    }

    void clearCommander() {
        this.commanderId = Integer.MIN_VALUE;
    }

    @Nullable
    public UUID playerDesignatedCommander() {
        return this.playerDesignatedCommander;
    }

    /** TODO(command-player-designation) */
    public void setPlayerDesignatedCommander(@Nullable UUID uuid) {
        this.playerDesignatedCommander = uuid;
    }

    void apply(AssignedGroup assigned) {
        this.memberIds = assigned.memberIds.clone();
        this.centroidX = assigned.centroidX;
        this.centroidZ = assigned.centroidZ;
        // Incumbent left the group — clear so the next election is a no-incumbent case.
        if (this.commanderId != Integer.MIN_VALUE && !contains(this.commanderId)) {
            this.commanderId = Integer.MIN_VALUE;
        }
    }

    ExistingGroup toExisting() {
        return new ExistingGroup(this.groupId, this.faction, this.memberIds.clone(),
                this.centroidX, this.centroidZ);
    }

    @Override
    public String toString() {
        return "BattleGroup{id=" + this.groupId + " faction=" + this.faction
                + " members=" + Arrays.toString(this.memberIds)
                + " commander=" + (hasCommander() ? this.commanderId : "none")
                + " c=(" + this.centroidX + "," + this.centroidZ + ")}";
    }
}
