package com.neoalive.tacz_sewv.block;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.ItemStackHandler;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.fob.FobSupport;
import com.neoalive.tacz_sewv.init.ModBlockEntities;
import com.neoalive.tacz_sewv.init.ModSounds;
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
    /** Radar poll period. The dish only has to look plausible, so this is deliberately slow. */
    private static final int SCAN_TICKS = 100;
    private static final AABB RENDER_EXTENT = new AABB(-2, 0, -2, 3, 6, 3);

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
    private long nextScan;
    /** Last spotted hostile, synced to the client, which slews the radar onto it. */
    @Nullable
    private Vec3 aim;
    /** Entity id of the last spotted hostile, so the contact ping only sounds on a new one. */
    private int lastTarget = -1;

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
        if (this.powered && level.getGameTime() >= this.nextScan) {
            this.nextScan = level.getGameTime() + SCAN_TICKS;
            scan(level);
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

    /**
     * Points the array at the nearest unit hostile to the owner inside the covered chunks. A crew
     * is aimed at through its hull. With no contact the last spotted position is kept.
     */
    private void scan(ServerLevel level) {
        if (this.owner == null) return;
        // Relations only: a creative owner is still somebody the enemy would shoot at.
        Player subject = level.getPlayerByUUID(this.owner);
        AbstractUnit pmc = subject == null ? FobSupport.ownerPerspective(level, this.owner) : null;
        if (subject == null && pmc == null) return;
        int reach = this.radius * 16 + 8;
        AABB box = new AABB(this.worldPosition).inflate(reach, level.getHeight(), reach);
        Vec3 from = Vec3.atCenterOf(this.worldPosition);
        AbstractUnit best = null;
        double bestDist = Double.MAX_VALUE;
        for (AbstractUnit unit : level.getEntitiesOfClass(AbstractUnit.class, box, u -> u.isAlive() && u != pmc
                && (subject != null ? !VehicleTargeting.isNonHostileIgnoringGameMode(u, subject)
                        : !VehicleTargeting.isNonHostile(pmc, u)))) {
            double d = unit.distanceToSqr(from);
            if (d < bestDist) {
                bestDist = d;
                best = unit;
            }
        }
        if (best == null) {
            this.lastTarget = -1;
            return;
        }
        Entity spotted = best.getVehicle() != null ? best.getVehicle() : best;
        if (spotted.getId() != this.lastTarget) {
            this.lastTarget = spotted.getId();
            level.playSound(null, this.worldPosition, ModSounds.FPAS_TARGET.get(), SoundSource.BLOCKS, 1.5F, 1.0F);
        }
        Vec3 next = spotted.getBoundingBox().getCenter();
        if (this.aim != null && this.aim.distanceToSqr(next) < 1.0) return;
        this.aim = next;
        setChanged();
        level.sendBlockUpdated(this.worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Nullable
    public Vec3 aim() {
        return this.aim;
    }

    @Override
    public AABB getRenderBoundingBox() {
        return RENDER_EXTENT.move(this.worldPosition);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
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
        if (this.aim != null) {
            tag.putDouble("AimX", this.aim.x);
            tag.putDouble("AimY", this.aim.y);
            tag.putDouble("AimZ", this.aim.z);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        this.radius = tag.contains("Radius") ? tag.getInt("Radius") : ZoneGeometry.MIN_RADIUS;
        this.powered = tag.getBoolean("Powered");
        this.energy.set(tag.getInt("Energy"));
        this.battery.deserializeNBT(tag.getCompound("Battery"));
        this.aim = tag.contains("AimX")
                ? new Vec3(tag.getDouble("AimX"), tag.getDouble("AimY"), tag.getDouble("AimZ")) : null;
    }
}
