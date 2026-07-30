package com.neoalive.tacz_sewv.block;

import com.neoalive.tacz_sewv.init.ModBlockEntities;
import com.neoalive.tacz_sewv.invasion.CapturableBlockEntity;
import com.neoalive.tacz_sewv.invasion.CaptureSupport;
import com.neoalive.tacz_sewv.invasion.InvasionLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Invasion capture node. Ascending {@link #pointId} drives the global AI pathing order (Stage F).
 */
public class CapturePointBlockEntity extends CapturableBlockEntity {

    public static final double DEFAULT_BILLBOARD_Y_OFFSET = 3.0;

    /** Sentinel until {@link InvasionLayout} assigns one on first server load. */
    public static final int UNASSIGNED_ID = -1;

    private int pointId = UNASSIGNED_ID;
    private boolean showBillboard = true;
    private double billboardYOffset = DEFAULT_BILLBOARD_Y_OFFSET;

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

    public boolean isShowBillboard() {
        return showBillboard;
    }

    public void setShowBillboard(boolean showBillboard) {
        this.showBillboard = showBillboard;
        setChanged();
    }

    public double getBillboardYOffset() {
        return billboardYOffset;
    }

    public void setBillboardYOffset(double billboardYOffset) {
        this.billboardYOffset = billboardYOffset;
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
    }

    /** Presence / capture tick while an {@link com.neoalive.tacz_sewv.invasion.InvasionSession} is active. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, CapturePointBlockEntity be) {
        CaptureSupport.tick(be);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("PointId", pointId);
        tag.putBoolean("ShowBillboard", showBillboard);
        tag.putDouble("BillboardYOffset", billboardYOffset);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        pointId = tag.contains("PointId") ? tag.getInt("PointId") : UNASSIGNED_ID;
        showBillboard = !tag.contains("ShowBillboard") || tag.getBoolean("ShowBillboard");
        billboardYOffset = tag.contains("BillboardYOffset")
                ? tag.getDouble("BillboardYOffset") : DEFAULT_BILLBOARD_Y_OFFSET;
    }
}
