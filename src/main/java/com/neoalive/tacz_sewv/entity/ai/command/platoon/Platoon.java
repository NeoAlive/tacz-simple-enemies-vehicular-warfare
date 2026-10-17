package com.neoalive.tacz_sewv.entity.ai.command.platoon;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Sticky platoon — one player's own PMC members, always trying to stay within
 * {@code SewvConfig.PLATOON_COHESION_RADIUS} of each other unless under an active
 * {@code BattleGroup} doctrine assignment. Mirrors {@link com.neoalive.tacz_sewv.entity.ai.command.BattleGroup}'s
 * shape but is not battle-gated and carries no influence/play state of its own.
 *
 * <p><b>Only ever forms around a live {@link com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity},
 * one platoon per commander</b> — {@link PlatoonRegistry} seeds a new platoon on each not-yet-leading
 * commander and fills it from that commander's own nearby candidates; ordinary units never cluster
 * into a leaderless platoon among themselves. {@code type()} is fixed at formation (never mixed),
 * but what the size cap counts is not: an {@code INFANTRY} platoon (commander on foot) caps at
 * {@code PLATOON_MAX_SIZE} people; a {@code GROUND_VEHICLE} one (commander seated in an eligible
 * hull) caps at {@code PLATOON_MAX_SIZE} <i>vehicles</i> instead, each contributing its whole crew
 * — see {@link PlatoonRegistry#enforceDismountedCap} for what happens when that commander gets out.
 *
 * <p><b>Membership is assigned once, at formation, and never re-clustered by distance</b> — a
 * member stays in the platoon regardless of how far it strays (the cohesion goal is what tries to
 * close that gap, not membership itself). It is only ever dropped by {@link PlatoonRegistry} when
 * confirmed dead/unloaded, by the player's own "Exit Platoon" order ({@link #removeMember}), or
 * for the whole platoon when the commander dies.
 *
 * <p>{@code groupId} is a simple monotonic id {@link PlatoonRegistry} assigns at formation and uses
 * as this platoon's map key; {@link #id()} is the platoon's real, stable identity, generated once
 * and never reassigned for the life of the object.
 */
public final class Platoon {

    public enum Type { INFANTRY, GROUND_VEHICLE }

    private final int groupId;
    private final UUID id;
    private final Type type;
    private final UUID owner;
    private final int colorRgb;
    private int[] memberIds;
    private double centroidX;
    private double centroidZ;

    /** Network id of the live Commander leading this platoon, or -1 if none is a member. */
    private int commanderId = -1;

    Platoon(int groupId, Type type, UUID owner, UUID commanderUuid, int[] memberIds, double centroidX, double centroidZ) {
        this.groupId = groupId;
        this.id = UUID.randomUUID();
        this.type = type;
        this.owner = owner;
        this.memberIds = memberIds.clone();
        this.centroidX = centroidX;
        this.centroidZ = centroidZ;
        // Seeded on the COMMANDER's persistent entity UUID, not this.id (a fresh random UUID
        // rolled on every formation) — a platoon never survives a world reload (GROUPS_BY_LEVEL is
        // cleared on server start/stop) and never outlives its commander (pruneExisting disbands it
        // the instant that commander dies), so the commander's own UUID is the only identity a
        // color can be "cached" against: the same commander always reforms the same colored platoon.
        this.colorRgb = randomColor(commanderUuid);
    }

    public UUID id() {
        return this.id;
    }

    public Type type() {
        return this.type;
    }

    public UUID owner() {
        return this.owner;
    }

    public int colorRgb() {
        return this.colorRgb;
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

    public boolean hasCommander() {
        return this.commanderId >= 0;
    }

    public int commanderId() {
        return this.commanderId;
    }

    void setCommanderId(int commanderId) {
        this.commanderId = commanderId;
    }

    int groupId() {
        return this.groupId;
    }

    /** Re-derive membership/centroid after a liveness prune — never adds, only drops the dead. */
    void applyMembers(int[] aliveIds, double centroidX, double centroidZ) {
        this.memberIds = aliveIds.clone();
        this.centroidX = centroidX;
        this.centroidZ = centroidZ;
    }

    /** Automatic or manual join — never re-adds an id already present. */
    void addMember(int unitId) {
        if (contains(unitId)) return;
        int[] updated = Arrays.copyOf(this.memberIds, this.memberIds.length + 1);
        updated[updated.length - 1] = unitId;
        Arrays.sort(updated);
        this.memberIds = updated;
    }

    /** Player-issued "Exit Platoon" — the one deliberate way a live member leaves early. */
    void removeMember(int unitId) {
        List<Integer> kept = new ArrayList<>(this.memberIds.length);
        for (int id : this.memberIds) {
            if (id != unitId) kept.add(id);
        }
        this.memberIds = kept.stream().mapToInt(Integer::intValue).toArray();
        if (this.commanderId == unitId) {
            this.commanderId = -1;
        }
    }

    /** Stable per-platoon color, legible against both map and UI backgrounds. */
    private static int randomColor(UUID seed) {
        float hue = (seed.getMostSignificantBits() & 0xFFFFFFFFL) / (float) 0xFFFFFFFFL;
        return Color.HSBtoRGB(hue, 0.65f, 0.95f) & 0xFFFFFF;
    }

    @Override
    public String toString() {
        return "Platoon{id=" + this.id + " type=" + this.type
                + " members=" + Arrays.toString(this.memberIds)
                + " commander=" + (hasCommander() ? this.commanderId : "none") + "}";
    }
}
