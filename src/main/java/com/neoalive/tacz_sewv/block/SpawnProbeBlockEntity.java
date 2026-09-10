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
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * Structure-prep spawn marker. Exclusive Vehicle XOR Infantry payload, plus a Faction Type
 * tag for downstream spawn scope. Editor + NBT only — no consumer yet.
 */
public class SpawnProbeBlockEntity extends BlockEntity {

    private SpawnProbeCategory category = SpawnProbeCategory.VEHICLE;
    private TankFaction factionType = TankFaction.RU;
    private final List<String> vehicleList = new ArrayList<>();
    private boolean preCrewedSpawn;
    private final List<SpawnProbeInfantryEntry> infantryList = new ArrayList<>();

    public SpawnProbeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SPAWN_PROBE.get(), pos, state);
    }

    public SpawnProbeCategory getCategory() {
        return category;
    }

    public void setCategory(SpawnProbeCategory category) {
        this.category = category == null ? SpawnProbeCategory.VEHICLE : category;
        setChanged();
    }

    public TankFaction getFactionType() {
        return factionType;
    }

    public void setFactionType(TankFaction factionType) {
        this.factionType = factionType == null ? TankFaction.RU : factionType;
        setChanged();
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

    public void clearVehicleList() {
        vehicleList.clear();
        setChanged();
    }

    public boolean isPreCrewedSpawn() {
        return preCrewedSpawn;
    }

    public void setPreCrewedSpawn(boolean preCrewedSpawn) {
        this.preCrewedSpawn = preCrewedSpawn;
        setChanged();
    }

    public List<SpawnProbeInfantryEntry> getInfantryList() {
        return infantryList;
    }

    public void setInfantryList(List<SpawnProbeInfantryEntry> entries) {
        infantryList.clear();
        if (entries != null) {
            for (SpawnProbeInfantryEntry entry : entries) {
                if (entry == null || entry.id().isEmpty()) continue;
                // Catalog ids, or already-saved / typed ids the op editor sent through.
                if (!SpawnProbeInfantryCatalog.isAllowed(entry.id())
                        && net.minecraft.resources.ResourceLocation.tryParse(entry.id()) == null) {
                    continue;
                }
                if (containsInfantryId(entry.id())) continue;
                infantryList.add(entry);
            }
        }
        setChanged();
    }

    public void clearInfantry() {
        infantryList.clear();
        setChanged();
    }

    /**
     * Apply editor payload: faction always; active category kept, inactive side cleared.
     */
    public void applyEditor(SpawnProbeCategory category, TankFaction faction,
                            List<String> vehicles, boolean preCrewed,
                            List<SpawnProbeInfantryEntry> infantry) {
        setFactionType(faction);
        setCategory(category);
        if (category == SpawnProbeCategory.INFANTRY) {
            setInfantryList(infantry);
            clearVehicleList();
            setPreCrewedSpawn(false);
        } else {
            setVehicleList(vehicles);
            setPreCrewedSpawn(preCrewed);
            clearInfantry();
        }
    }

    private boolean containsInfantryId(String id) {
        for (SpawnProbeInfantryEntry e : infantryList) {
            if (e.id().equals(id)) return true;
        }
        return false;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("Category", category.name());
        tag.putString("FactionType", factionType.name());
        ListTag vehicles = new ListTag();
        for (String id : vehicleList) {
            vehicles.add(StringTag.valueOf(id));
        }
        tag.put("VehicleList", vehicles);
        tag.putBoolean("PreCrewedSpawn", preCrewedSpawn);
        ListTag infantry = new ListTag();
        for (SpawnProbeInfantryEntry entry : infantryList) {
            infantry.add(entry.save());
        }
        tag.put("InfantryList", infantry);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        category = SpawnProbeCategory.parse(tag.getString("Category"));
        try {
            factionType = TankFaction.valueOf(tag.getString("FactionType"));
        } catch (IllegalArgumentException e) {
            factionType = TankFaction.RU;
        }
        vehicleList.clear();
        if (tag.contains("VehicleList", Tag.TAG_LIST)) {
            ListTag list = tag.getList("VehicleList", Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                String id = list.getString(i);
                if (!id.isEmpty() && !vehicleList.contains(id)) vehicleList.add(id);
            }
        }
        preCrewedSpawn = tag.getBoolean("PreCrewedSpawn");
        infantryList.clear();
        if (tag.contains("InfantryList", Tag.TAG_LIST)) {
            ListTag list = tag.getList("InfantryList", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                SpawnProbeInfantryEntry entry = SpawnProbeInfantryEntry.load(list.getCompound(i));
                if (entry == null || entry.id().isEmpty()) continue;
                if (containsInfantryId(entry.id())) continue;
                infantryList.add(entry);
            }
        }
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
