package com.neoalive.tacz_sewv.block;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.neoalive.tacz_sewv.init.ModBlockEntities;

/**
 * Structure-prep spawn marker. Stores the vehicle id pool and whether structure placement
 * should crew the hull (same path as berezka later) or leave it empty.
 */
public class SpawnProbeBlockEntity extends BlockEntity {

    private final List<String> vehicleList = new ArrayList<>();
    private boolean preCrewedSpawn;

    public SpawnProbeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SPAWN_PROBE.get(), pos, state);
    }

    public List<String> getVehicleList() {
        return vehicleList;
    }

    public void setVehicleList(List<String> ids) {
        vehicleList.clear();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.isEmpty() && !vehicleList.contains(id)) {
                    vehicleList.add(id);
                }
            }
        }
        setChanged();
    }

    public boolean isPreCrewedSpawn() {
        return preCrewedSpawn;
    }

    public void setPreCrewedSpawn(boolean preCrewedSpawn) {
        this.preCrewedSpawn = preCrewedSpawn;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ListTag list = new ListTag();
        for (String id : vehicleList) {
            list.add(StringTag.valueOf(id));
        }
        tag.put("VehicleList", list);
        tag.putBoolean("PreCrewedSpawn", preCrewedSpawn);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        vehicleList.clear();
        if (tag.contains("VehicleList", Tag.TAG_LIST)) {
            ListTag list = tag.getList("VehicleList", Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                String id = list.getString(i);
                if (!id.isEmpty() && !vehicleList.contains(id)) vehicleList.add(id);
            }
        }
        preCrewedSpawn = tag.getBoolean("PreCrewedSpawn");
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
