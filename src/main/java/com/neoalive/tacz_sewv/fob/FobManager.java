package com.neoalive.tacz_sewv.fob;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

public class FobManager extends SavedData {

    public static final String DATA_NAME = "tacz_sewv_fobs";

    private final Map<BlockPos, FobInstance> fobs = new HashMap<>();

    public FobManager() {}

    public static FobManager load(CompoundTag tag) {
        FobManager mgr = new FobManager();
        if (!tag.contains("fobs", Tag.TAG_LIST)) return mgr;
        ListTag list = tag.getList("fobs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            FobInstance fob = FobInstance.read(list.getCompound(i));
            mgr.fobs.put(fob.commandPos, fob);
        }
        return mgr;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (FobInstance fob : this.fobs.values()) {
            CompoundTag e = new CompoundTag();
            fob.write(e);
            list.add(e);
        }
        tag.put("fobs", list);
        return tag;
    }

    public static FobManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FobManager::load, FobManager::new, DATA_NAME);
    }

    @Nullable
    public FobInstance getFob(BlockPos commandPos) {
        return this.fobs.get(commandPos);
    }

    @Nullable
    public FobInstance getFobForOwner(UUID owner) {
        for (FobInstance fob : this.fobs.values()) {
            if (owner.equals(fob.owner)) return fob;
        }
        return null;
    }

    @Nullable
    public FobInstance getFobAt(BlockPos pos, Level level) {
        for (FobInstance fob : this.fobs.values()) {
            if (fob.cachedMasterAabb == null) {
                FobSupport.refreshCachedAabbs(fob, level);
            }
            AABB box = fob.cachedMasterAabb;
            if (box != null && box.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
                return fob;
            }
        }
        return null;
    }

    public boolean addFob(BlockPos commandPos, UUID owner, Level level) {
        if (getFobForOwner(owner) != null) return false;
        FobInstance fob = new FobInstance(commandPos, owner);
        FobSupport.refreshCachedAabbs(fob, level);
        fob.valid = false;
        fob.invalidReason = "Missing stockpile or parking field";
        this.fobs.put(commandPos, fob);
        setDirty();
        return true;
    }

    public void removeFob(BlockPos commandPos, ServerLevel level) {
        FobInstance fob = this.fobs.remove(commandPos);
        if (fob == null) return;
        clearStamps(fob, level);
        setDirty();
    }

    private void clearStamps(FobInstance fob, ServerLevel level) {
        for (UUID id : fob.assignedLiving) {
            Entity e = findEntity(level, id);
            if (e != null) {
                FobSupport.clearStamp(e);
                FobSupport.clearRoutePending(e);
                FobDebug.logEntity(e, "cleared stamp — FOB removed at {}", fob.commandPos);
            }
        }
        for (UUID id : fob.assignedVehicles) {
            Entity e = findEntity(level, id);
            if (e != null) {
                FobSupport.clearStamp(e);
                FobSupport.clearRoutePending(e);
                FobDebug.logEntity(e, "cleared vehicle stamp — FOB removed at {}", fob.commandPos);
            }
        }
    }

    @Nullable
    private static Entity findEntity(ServerLevel level, UUID id) {
        Entity e = level.getEntity(id);
        if (e != null) return e;
        for (ServerLevel dim : level.getServer().getAllLevels()) {
            e = dim.getEntity(id);
            if (e != null) return e;
        }
        return null;
    }

    public void linkSubBlock(BlockPos commandPos, BlockPos subPos, String type, Level level) {
        FobInstance fob = this.fobs.get(commandPos);
        if (fob == null) return;
        if ("stockpile".equals(type)) {
            fob.stockpilePos = subPos;
        } else if ("parking".equals(type)) {
            fob.parkingPos = subPos;
        }
        validate(commandPos, level);
        setDirty();
    }

    public void unlinkSubBlock(BlockPos commandPos, BlockPos subPos, Level level) {
        FobInstance fob = this.fobs.get(commandPos);
        if (fob == null) return;
        if (subPos.equals(fob.stockpilePos)) fob.stockpilePos = null;
        if (subPos.equals(fob.parkingPos)) fob.parkingPos = null;
        validate(commandPos, level);
        setDirty();
    }

    public void validate(BlockPos commandPos, Level level) {
        FobInstance fob = this.fobs.get(commandPos);
        if (fob == null) return;
        FobSupport.refreshCachedAabbs(fob, level);
        pruneDeadAssignments(fob, level);

        if (level instanceof ServerLevel server) {
            FobClearance.Result clearance = FobClearance.check(fob, server);
            fob.valid = clearance.valid();
            fob.invalidReason = clearance.valid() ? "" : clearance.reason();
        } else {
            fob.valid = false;
            fob.invalidReason = "";
        }
        setDirty();
    }

    public void pruneDeadAssignments(FobInstance fob, Level level) {
        if (!(level instanceof ServerLevel server)) return;
        pruneSet(fob.assignedLiving, server);
        pruneVehicles(fob, server);
    }

    private void pruneVehicles(FobInstance fob, ServerLevel level) {
        Iterator<UUID> it = fob.assignedVehicles.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            Entity e = findEntity(level, id);
            if (e == null || !e.isAlive()) {
                it.remove();
                continue;
            }
            if (!(e instanceof VehicleEntity hull)) {
                it.remove();
                continue;
            }
            if (!FobSupport.vehicleOwnedBy(hull, fob.owner)) {
                it.remove();
                FobDebug.logEntity(hull, "unassigned from FOB — stolen or no longer player-owned");
            }
        }
    }

    private void pruneSet(java.util.Set<UUID> ids, ServerLevel level) {
        Iterator<UUID> it = ids.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            Entity e = findEntity(level, id);
            if (e == null || !e.isAlive()) it.remove();
        }
    }

    public boolean assignLiving(BlockPos commandPos, UUID entityId, ServerLevel level) {
        FobInstance fob = this.fobs.get(commandPos);
        if (fob == null) return false;
        Entity e = findEntity(level, entityId);
        if (!(e instanceof PmcUnitEntity pmc)) return false;
        fob.assignedLiving.add(entityId);
        FobSupport.stamp(pmc, commandPos);
        FobDebug.logEntity(pmc, "assigned to FOB at {}", commandPos);
        pruneDeadAssignments(fob, level);
        setDirty();
        return true;
    }

    public boolean unassignLiving(BlockPos commandPos, UUID entityId, ServerLevel level) {
        FobInstance fob = this.fobs.get(commandPos);
        if (fob == null) return false;
        fob.assignedLiving.remove(entityId);
        Entity e = findEntity(level, entityId);
        if (e != null) {
            FobSupport.clearStamp(e);
            FobSupport.clearRoutePending(e);
            FobDebug.logEntity(e, "unassigned from FOB at {}", commandPos);
        }
        setDirty();
        return true;
    }

    public boolean assignVehicle(BlockPos commandPos, UUID vehicleId, ServerLevel level) {
        FobInstance fob = this.fobs.get(commandPos);
        if (fob == null) return false;
        Entity e = findEntity(level, vehicleId);
        if (!(e instanceof VehicleEntity hull)) return false;
        if (!FobSupport.vehicleOwnedBy(hull, fob.owner)) return false;
        fob.assignedVehicles.add(vehicleId);
        FobSupport.stamp(hull, commandPos);
        pruneDeadAssignments(fob, level);
        setDirty();
        return true;
    }

    public boolean unassignVehicle(BlockPos commandPos, UUID vehicleId, ServerLevel level) {
        FobInstance fob = this.fobs.get(commandPos);
        if (fob == null) return false;
        fob.assignedVehicles.remove(vehicleId);
        Entity e = findEntity(level, vehicleId);
        if (e != null) {
            FobSupport.clearStamp(e);
            FobSupport.clearRoutePending(e);
            FobDebug.logEntity(e, "unassigned from FOB at {}", commandPos);
        }
        setDirty();
        return true;
    }

    public void toggleFobCommand(BlockPos commandPos) {
        FobInstance fob = this.fobs.get(commandPos);
        if (fob == null) return;
        fob.fobCommandActive = !fob.fobCommandActive;
        setDirty();
    }

    public Iterable<FobInstance> all() {
        return this.fobs.values();
    }

    public static void denyPlacement(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }
}
