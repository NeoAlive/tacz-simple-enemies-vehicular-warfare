package com.neoalive.tacz_sewv.block;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import com.neoalive.tacz_sewv.init.ModBlockEntities;
import com.neoalive.tacz_sewv.invasion.CapturableBlockEntity;
import com.neoalive.tacz_sewv.invasion.CaptureSupport;
import com.neoalive.tacz_sewv.invasion.InvasionLayout;

/**
 * Invasion capture node. Point ID is a builder label only — AI order is vicinity to each
 * team's own team_base (Stage G1).
 */
public class CapturePointBlockEntity extends CapturableBlockEntity {

    /** Sentinel until {@link InvasionLayout} assigns one on first server load. */
    public static final int UNASSIGNED_ID = -1;

    private int pointId = UNASSIGNED_ID;

    public CapturePointBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CAPTURE_POINT.get(), pos, state);
    }

    public int getPointId() {
        return pointId;
    }

    public void setPointId(int pointId) {
        this.pointId = pointId;
        if (level instanceof ServerLevel serverLevel && pointId >= 0) {
            InvasionLayout.get(serverLevel).noteExistingId(pointId);
        }
        setChanged();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel && pointId == UNASSIGNED_ID) {
            pointId = InvasionLayout.get(serverLevel).claimNextId();
            setChanged();
        } else if (level instanceof ServerLevel serverLevel && pointId >= 0) {
            InvasionLayout.get(serverLevel).noteExistingId(pointId);
        }
        if (level instanceof ServerLevel serverLevel) {
            InvasionLayout.get(serverLevel).noteCapturePoint(getBlockPos());
        }
    }

    /** Presence / capture tick while an {@link com.neoalive.tacz_sewv.invasion.InvasionSession} is active. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, CapturePointBlockEntity be) {
        CaptureSupport.tick(be);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("PointId", pointId);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        pointId = tag.contains("PointId") ? tag.getInt("PointId") : UNASSIGNED_ID;
    }
}
