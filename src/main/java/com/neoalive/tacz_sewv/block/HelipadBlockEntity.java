package com.neoalive.tacz_sewv.block;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import com.neoalive.tacz_sewv.airport.HelipadClearance;
import com.neoalive.tacz_sewv.airport.HelipadRegistry;
import com.neoalive.tacz_sewv.init.ModBlockEntities;

/**
 * The helipad master's cache: whether Check Clearance has passed. The reserved volume is derived
 * from the position, so the flag is all there is to store.
 */
public class HelipadBlockEntity extends BlockEntity {

    private boolean cleared;
    /** See RunwayBlockEntity: chunk unload runs onChunkUnloaded before setRemoved, a real break does not. */
    private boolean chunkUnloaded;

    public HelipadBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HELIPAD.get(), pos, state);
    }

    public boolean isCleared() {
        return this.cleared;
    }

    /** The baked volume, or null while the pad is not cleared. */
    @Nullable
    public AABB cachedVolume() {
        return this.cleared ? HelipadClearance.volume(this.worldPosition) : null;
    }

    public void applyClearance() {
        this.cleared = true;
        if (this.level instanceof ServerLevel server) HelipadRegistry.get(server).note(this.worldPosition);
        syncClient();
    }

    public void clearClearance() {
        this.cleared = false;
        if (this.level instanceof ServerLevel server) HelipadRegistry.get(server).forget(this.worldPosition);
        syncClient();
    }

    private void syncClient() {
        setChanged();
        if (this.level != null && !this.level.isClientSide) {
            this.level.sendBlockUpdated(this.worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.cleared && this.level instanceof ServerLevel server) {
            HelipadRegistry.get(server).note(this.worldPosition);
        }
    }

    @Override
    public void onChunkUnloaded() {
        this.chunkUnloaded = true;
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        // Real break only: walking away must leave the registry so a land order still resolves.
        if (!this.chunkUnloaded && this.level instanceof ServerLevel server) {
            HelipadRegistry.get(server).forget(this.worldPosition);
        }
        super.setRemoved();
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("Cleared", this.cleared);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.cleared = tag.getBoolean("Cleared");
    }
}
