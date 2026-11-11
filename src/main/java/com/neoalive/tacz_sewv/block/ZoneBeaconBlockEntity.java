package com.neoalive.tacz_sewv.block;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.ItemStackHandler;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.init.ModBlockEntities;
import com.neoalive.tacz_sewv.inventory.ZoneBeaconMenu;
import com.neoalive.tacz_sewv.zone.SpawnZoneData;
import com.neoalive.tacz_sewv.zone.ZoneGeometry;

/**
 * Powered beacon that keeps a square of chunks around itself a no-zone. Takes FE from any side
 * (an SBW charging station next to it pushes on its own) or from a battery in its slot. Only
 * power transitions and radius changes reach the zone data, so a zone never flickers.
 */
public class ZoneBeaconBlockEntity extends BlockEntity implements MenuProvider {

    /** Ticks without enough energy before the zone drops. */
    public static final int STARVE_TICKS = 20;
    /** Seconds of upkeep the buffer must hold before a starved beacon powers back up. */
    private static final int RESUME_TICKS = 20;
    private static final int BATTERY_DRAIN_PER_TICK = 4000;
    /** setChanged also pokes comparator neighbours, so steady draw is only flagged this often. */
    private static final int SAVE_EVERY_TICKS = 20;

    /** Receive-only from outside; the beacon spends from it itself. */
    private static final class Buffer extends EnergyStorage {
        Buffer(int capacity) {
            super(capacity, capacity, 0);
        }

        void spend(int amount) {
            this.energy = Math.max(0, this.energy - amount);
        }

        void set(int amount) {
            this.energy = Math.max(0, Math.min(this.capacity, amount));
        }

        void resize(int capacity) {
            this.capacity = capacity;
            this.maxReceive = capacity;
            this.energy = Math.min(this.energy, capacity);
        }
    }

    private final Buffer energy = new Buffer(bufferSize());
    private final ItemStackHandler battery = new ItemStackHandler(1) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return stack.getCapability(ForgeCapabilities.ENERGY).map(IEnergyStorage::canExtract).orElse(false);
        }

        @Override
        protected void onContentsChanged(int slot) {
            ZoneBeaconBlockEntity.this.setChanged();
        }
    };
    private LazyOptional<IEnergyStorage> energyCap = LazyOptional.empty();
    private LazyOptional<ItemStackHandler> itemCap = LazyOptional.empty();

    @Nullable
    private UUID owner;
    private int radius = ZoneGeometry.MIN_RADIUS;
    private boolean powered;
    private int starved;
    private boolean chunkUnloaded;
    private int sinceSave;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int i) {
            int cost = (int) Math.min(Integer.MAX_VALUE, upkeep());
            return switch (i) {
                case 0 -> energy.getEnergyStored() & 0xFFFF;
                case 1 -> energy.getEnergyStored() >>> 16;
                case 2 -> energy.getMaxEnergyStored() & 0xFFFF;
                case 3 -> energy.getMaxEnergyStored() >>> 16;
                case 4 -> radius;
                case 5 -> powered ? 1 : 0;
                case 6 -> cost & 0xFFFF;
                case 7 -> cost >>> 16;
                case 8 -> maxRadius();
                default -> 0;
            };
        }

        @Override
        public void set(int i, int value) {}

        @Override
        public int getCount() {
            return 9;
        }
    };

    public ZoneBeaconBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ZONE_BEACON.get(), pos, state);
    }

    private static int bufferSize() {
        try {
            return SewvConfig.ZONE_BEACON_BUFFER_FE.get();
        } catch (IllegalStateException e) {
            return 100000;
        }
    }

    private static int maxRadius() {
        try {
            return SewvConfig.ZONE_BEACON_MAX_RADIUS.get();
        } catch (IllegalStateException e) {
            return ZoneGeometry.MAX_RADIUS;
        }
    }

    public long upkeep() {
        return ZoneGeometry.upkeepPerTick(this.radius, SewvConfig.ZONE_BEACON_FE_PER_CHUNK.get());
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ZoneBeaconBlockEntity be) {
        be.tickServer((ServerLevel) level);
    }

    private void tickServer(ServerLevel level) {
        if (this.energy.getMaxEnergyStored() != bufferSize()) this.energy.resize(bufferSize());
        drainBattery();
        long cost = upkeep();
        boolean was = this.powered;
        if (this.powered) {
            if (this.energy.getEnergyStored() >= cost) {
                this.energy.spend((int) cost);
                this.starved = 0;
            } else if (++this.starved >= STARVE_TICKS) {
                this.powered = false;
                this.starved = 0;
            }
        } else if (this.energy.getEnergyStored() >= Math.min(cost * RESUME_TICKS, this.energy.getMaxEnergyStored())) {
            this.powered = true;
            this.energy.spend((int) cost);
        }
        if (was != this.powered) {
            publish(level);
            setChanged();
            this.sinceSave = 0;
        } else if (++this.sinceSave >= SAVE_EVERY_TICKS) {
            setChanged();
            this.sinceSave = 0;
        }
    }

    private void drainBattery() {
        ItemStack stack = this.battery.getStackInSlot(0);
        if (stack.isEmpty()) return;
        int room = this.energy.getMaxEnergyStored() - this.energy.getEnergyStored();
        if (room <= 0) return;
        stack.getCapability(ForgeCapabilities.ENERGY).ifPresent(src -> {
            int got = src.extractEnergy(Math.min(room, BATTERY_DRAIN_PER_TICK), false);
            if (got > 0) this.energy.receiveEnergy(got, false);
        });
    }

    private void publish(ServerLevel level) {
        SpawnZoneData.get(level).putBeacon(this.worldPosition, this.owner, this.radius, this.powered);
    }

    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
        setChanged();
        if (this.level instanceof ServerLevel s) publish(s);
    }

    public boolean mayEdit(Player player) {
        return player.hasPermissions(2) || (this.owner != null && this.owner.equals(player.getUUID()));
    }

    /** Steps the radius by {@code delta}. Returns true if it changed. */
    public boolean stepRadius(int delta) {
        int next = ZoneGeometry.clampRadius(this.radius + delta, maxRadius());
        if (next == this.radius) return false;
        this.radius = next;
        setChanged();
        if (this.level instanceof ServerLevel s) publish(s);
        return true;
    }

    public ItemStackHandler battery() {
        return this.battery;
    }

    public ContainerData data() {
        return this.data;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        this.energyCap = LazyOptional.of(() -> this.energy);
        this.itemCap = LazyOptional.of(() -> this.battery);
        this.radius = ZoneGeometry.clampRadius(this.radius, maxRadius());
        if (this.level instanceof ServerLevel s) publish(s);
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        this.chunkUnloaded = true;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // Unloading keeps the zone frozen at its last state; only a real removal drops it.
        if (!this.chunkUnloaded && this.level instanceof ServerLevel s) {
            SpawnZoneData.get(s).removeBeacon(this.worldPosition);
        }
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        this.energyCap.invalidate();
        this.itemCap.invalidate();
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ENERGY) return this.energyCap.cast();
        if (cap == ForgeCapabilities.ITEM_HANDLER) return this.itemCap.cast();
        return super.getCapability(cap, side);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.tacz_sewv.zone_beacon");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new ZoneBeaconMenu(id, inv, this, this.data);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (this.owner != null) tag.putUUID("Owner", this.owner);
        tag.putInt("Radius", this.radius);
        tag.putBoolean("Powered", this.powered);
        tag.putInt("Energy", this.energy.getEnergyStored());
        tag.put("Battery", this.battery.serializeNBT());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        this.radius = tag.contains("Radius") ? tag.getInt("Radius") : ZoneGeometry.MIN_RADIUS;
        this.powered = tag.getBoolean("Powered");
        this.energy.set(tag.getInt("Energy"));
        this.battery.deserializeNBT(tag.getCompound("Battery"));
    }
}
