package com.neoalive.tacz_sewv.invasion;

import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-dimension invasion layout: capture-point ID allocator, known node positions, and
 * a persisted {@code sessionActive} flag so a reload can force-stop orphaned matches (Stage G).
 */
public class InvasionLayout extends SavedData {

    private static final String DATA_NAME = "tacz_sewv_invasion_layout";

    private int nextCapturePointId;
    private boolean sessionActive;
    private final Set<Long> capturePoints = new LinkedHashSet<>();
    private final Set<Long> teamBases = new LinkedHashSet<>();

    public InvasionLayout() {
    }

    public static InvasionLayout load(CompoundTag nbt) {
        InvasionLayout data = new InvasionLayout();
        data.nextCapturePointId = Math.max(0, nbt.getInt("NextCapturePointId"));
        data.sessionActive = nbt.getBoolean("SessionActive");
        readPosSet(nbt.getList("CapturePoints", Tag.TAG_LONG), data.capturePoints);
        readPosSet(nbt.getList("TeamBases", Tag.TAG_LONG), data.teamBases);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        nbt.putInt("NextCapturePointId", nextCapturePointId);
        nbt.putBoolean("SessionActive", sessionActive);
        nbt.put("CapturePoints", writePosSet(capturePoints));
        nbt.put("TeamBases", writePosSet(teamBases));
        return nbt;
    }

    public static InvasionLayout get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(InvasionLayout::load, InvasionLayout::new, DATA_NAME);
    }

    public int claimNextId() {
        int id = nextCapturePointId++;
        setDirty();
        return id;
    }

    public void noteExistingId(int id) {
        if (id >= nextCapturePointId) {
            nextCapturePointId = id + 1;
            setDirty();
        }
    }

    public boolean isSessionActive() {
        return sessionActive;
    }

    public void setSessionActive(boolean sessionActive) {
        if (this.sessionActive == sessionActive) return;
        this.sessionActive = sessionActive;
        setDirty();
    }

    public void noteCapturePoint(BlockPos pos) {
        if (capturePoints.add(pos.asLong())) setDirty();
    }

    public void noteTeamBase(BlockPos pos) {
        if (teamBases.add(pos.asLong())) setDirty();
    }

    public void forgetCapturePoint(BlockPos pos) {
        if (capturePoints.remove(pos.asLong())) setDirty();
    }

    public void forgetTeamBase(BlockPos pos) {
        if (teamBases.remove(pos.asLong())) setDirty();
    }

    public Set<Long> capturePointPositions() {
        return Set.copyOf(capturePoints);
    }

    public Set<Long> teamBasePositions() {
        return Set.copyOf(teamBases);
    }

    private static void readPosSet(ListTag list, Set<Long> out) {
        out.clear();
        for (int i = 0; i < list.size(); i++) {
            out.add(((LongTag) list.get(i)).getAsLong());
        }
    }

    private static ListTag writePosSet(Set<Long> set) {
        ListTag list = new ListTag();
        for (long pos : set) {
            list.add(LongTag.valueOf(pos));
        }
        return list;
    }
}
