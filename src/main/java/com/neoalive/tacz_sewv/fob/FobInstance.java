package com.neoalive.tacz_sewv.fob;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.AABB;

/**
 * One player-owned Forward Operating Base keyed on the quarters_bench position.
 */
public final class FobInstance {

    public BlockPos commandPos;
    public UUID owner;
    @Nullable
    public BlockPos stockpilePos;
    @Nullable
    public BlockPos parkingPos;
    public final Set<UUID> assignedLiving = new HashSet<>();
    public final Set<UUID> assignedVehicles = new HashSet<>();
    public boolean valid;
    public String invalidReason = "";
    public long lastAlarmTime;
    public boolean fobCommandActive = true;
    public long lastThreatEvalTime;
    public boolean scrambleActive;
    public int threatScore;

    /** Rebuilt on validate — not serialized. */
    @Nullable
    public transient AABB cachedMasterAabb;
    @Nullable
    public transient AABB cachedBufferAabb;
    @Nullable
    public transient AABB cachedStockpileAabb;
    @Nullable
    public transient AABB cachedParkingAabb;

    public FobInstance() {}

    public FobInstance(BlockPos commandPos, UUID owner) {
        this.commandPos = commandPos;
        this.owner = owner;
    }

    public void write(CompoundTag tag) {
        tag.putLong("command", this.commandPos.asLong());
        tag.putUUID("owner", this.owner);
        if (this.stockpilePos != null) tag.putLong("stockpile", this.stockpilePos.asLong());
        if (this.parkingPos != null) tag.putLong("parking", this.parkingPos.asLong());
        tag.put("living", uuidList(this.assignedLiving));
        tag.put("vehicles", uuidList(this.assignedVehicles));
        tag.putBoolean("valid", this.valid);
        tag.putString("invalidReason", this.invalidReason == null ? "" : this.invalidReason);
        tag.putLong("lastAlarm", this.lastAlarmTime);
        tag.putBoolean("commandActive", this.fobCommandActive);
        tag.putLong("lastThreatEval", this.lastThreatEvalTime);
        tag.putBoolean("scramble", this.scrambleActive);
        tag.putInt("threatScore", this.threatScore);
    }

    public static FobInstance read(CompoundTag tag) {
        FobInstance fob = new FobInstance();
        fob.commandPos = BlockPos.of(tag.getLong("command"));
        fob.owner = tag.getUUID("owner");
        if (tag.contains("stockpile")) fob.stockpilePos = BlockPos.of(tag.getLong("stockpile"));
        if (tag.contains("parking")) fob.parkingPos = BlockPos.of(tag.getLong("parking"));
        readUuidList(tag.getList("living", Tag.TAG_COMPOUND), fob.assignedLiving);
        readUuidList(tag.getList("vehicles", Tag.TAG_COMPOUND), fob.assignedVehicles);
        fob.valid = tag.getBoolean("valid");
        fob.invalidReason = tag.getString("invalidReason");
        fob.lastAlarmTime = tag.getLong("lastAlarm");
        fob.fobCommandActive = !tag.contains("commandActive") || tag.getBoolean("commandActive");
        fob.lastThreatEvalTime = tag.getLong("lastThreatEval");
        fob.scrambleActive = tag.getBoolean("scramble");
        fob.threatScore = tag.getInt("threatScore");
        return fob;
    }

    private static ListTag uuidList(Set<UUID> uuids) {
        ListTag list = new ListTag();
        for (UUID id : uuids) {
            CompoundTag e = new CompoundTag();
            e.putUUID("id", id);
            list.add(e);
        }
        return list;
    }

    private static void readUuidList(ListTag list, Set<UUID> out) {
        out.clear();
        for (int i = 0; i < list.size(); i++) {
            out.add(list.getCompound(i).getUUID("id"));
        }
    }
}
