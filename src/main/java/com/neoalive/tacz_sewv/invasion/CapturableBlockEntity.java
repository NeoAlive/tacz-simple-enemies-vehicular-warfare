package com.neoalive.tacz_sewv.invasion;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Shared radius/time capture state for {@code capture_point} and {@code team_base}.
 * Capture ticking lives in {@link CaptureSupport}.
 */
public abstract class CapturableBlockEntity extends BlockEntity {

    public static final int DEFAULT_RADIUS = 16;
    public static final int DEFAULT_TIME_TO_CAPTURE_SECONDS = 30;

    private int radiusInBlocks = DEFAULT_RADIUS;
    private int timeToCaptureSeconds = DEFAULT_TIME_TO_CAPTURE_SECONDS;
    /** Empty string = unowned. */
    private String ownedTeam = "";
    /** 0..1 while a sole team is capturing; frozen when empty or contested. */
    private float progress;
    private boolean contested;

    /** Session-volatile: last presence scan game time. Not saved. */
    private long lastScanGameTime = Long.MIN_VALUE;
    /** Session-volatile: team currently advancing progress. Not saved. */
    private String advancingTeam = "";

    protected CapturableBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public int getRadiusInBlocks() {
        return radiusInBlocks;
    }

    public void setRadiusInBlocks(int radiusInBlocks) {
        this.radiusInBlocks = Math.max(1, radiusInBlocks);
        setChanged();
    }

    public int getTimeToCaptureSeconds() {
        return timeToCaptureSeconds;
    }

    public void setTimeToCaptureSeconds(int timeToCaptureSeconds) {
        this.timeToCaptureSeconds = Math.max(1, timeToCaptureSeconds);
        setChanged();
    }

    public String getOwnedTeam() {
        return ownedTeam;
    }

    public void setOwnedTeam(@Nullable String ownedTeam) {
        this.ownedTeam = ownedTeam == null ? "" : ownedTeam;
        setChanged();
    }

    public boolean hasOwner() {
        return !ownedTeam.isEmpty();
    }

    public float getProgress() {
        return progress;
    }

    public void setProgress(float progress) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        setChanged();
    }

    /** @return true if the value changed */
    public boolean setProgressIfChanged(float progress) {
        float clamped = Math.max(0f, Math.min(1f, progress));
        if (Float.compare(this.progress, clamped) == 0) return false;
        this.progress = clamped;
        setChanged();
        return true;
    }

    public boolean isContested() {
        return contested;
    }

    public void setContested(boolean contested) {
        this.contested = contested;
        setChanged();
    }

    /** @return true if the value changed */
    public boolean setContestedIfChanged(boolean contested) {
        if (this.contested == contested) return false;
        this.contested = contested;
        setChanged();
        return true;
    }

    public long getLastScanGameTime() {
        return lastScanGameTime;
    }

    public void setLastScanGameTime(long lastScanGameTime) {
        this.lastScanGameTime = lastScanGameTime;
    }

    public String getAdvancingTeam() {
        return advancingTeam;
    }

    public void setAdvancingTeam(@Nullable String advancingTeam) {
        this.advancingTeam = advancingTeam == null ? "" : advancingTeam;
    }

    /** Clears session-volatile capture state. Ownership reset is caller's choice. */
    public void clearCaptureProgress() {
        this.progress = 0f;
        this.contested = false;
        this.advancingTeam = "";
        this.lastScanGameTime = Long.MIN_VALUE;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("Radius", radiusInBlocks);
        tag.putInt("TimeToCapture", timeToCaptureSeconds);
        tag.putString("OwnedTeam", ownedTeam);
        tag.putFloat("Progress", progress);
        tag.putBoolean("Contested", contested);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        radiusInBlocks = tag.contains("Radius")
                ? Math.max(1, tag.getInt("Radius")) : DEFAULT_RADIUS;
        timeToCaptureSeconds = tag.contains("TimeToCapture")
                ? Math.max(1, tag.getInt("TimeToCapture")) : DEFAULT_TIME_TO_CAPTURE_SECONDS;
        ownedTeam = tag.getString("OwnedTeam");
        progress = tag.getFloat("Progress");
        contested = tag.getBoolean("Contested");
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
}
