package com.neoalive.tacz_sewv.block;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import com.neoalive.tacz_sewv.entity.SandbagSeatEntity;
import com.neoalive.tacz_sewv.init.ModBlockEntities;
import com.neoalive.tacz_sewv.init.ModEntities;

public class SandbagBlockEntity extends BlockEntity {

    private static final String TAG_SEAT = "SeatUUID";

    @Nullable
    private UUID seatId;

    public SandbagBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SANDBAG.get(), pos, state);
    }

    /** Create or reclaim the invisible seat for this sandbag. */
    public SandbagSeatEntity ensureSeat(ServerLevel level) {
        SandbagSeatEntity existing = findSeat(level);
        if (existing != null && existing.isAlive()) {
            snap(existing);
            return existing;
        }
        SandbagSeatEntity seat = ModEntities.SANDBAG_SEAT.get().create(level);
        if (seat == null) {
            throw new IllegalStateException("sandbag_seat type failed to create");
        }
        snap(seat);
        level.addFreshEntity(seat);
        this.seatId = seat.getUUID();
        setChanged();
        return seat;
    }

    public void discardSeat(ServerLevel level) {
        SandbagSeatEntity seat = findSeat(level);
        if (seat != null) {
            seat.ejectPassengers();
            seat.discard();
        }
        this.seatId = null;
        setChanged();
    }

    private void snap(SandbagSeatEntity seat) {
        Direction facing = Direction.NORTH;
        BlockState state = getBlockState();
        if (state.hasProperty(SandbagBlock.FACING)) {
            facing = state.getValue(SandbagBlock.FACING);
        }
        seat.bindTo(this.worldPosition, facing);
    }

    @Nullable
    private SandbagSeatEntity findSeat(ServerLevel level) {
        if (this.seatId != null) {
            Entity e = level.getEntity(this.seatId);
            if (e instanceof SandbagSeatEntity seat) return seat;
        }
        // Self-heal: scan for a seat already bound to this block.
        AABB box = new AABB(this.worldPosition).inflate(0.75D);
        for (SandbagSeatEntity seat : level.getEntitiesOfClass(SandbagSeatEntity.class, box)) {
            if (this.worldPosition.equals(seat.getSandbagPos())) {
                this.seatId = seat.getUUID();
                return seat;
            }
        }
        return null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (this.seatId != null) {
            tag.putUUID(TAG_SEAT, this.seatId);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.seatId = tag.hasUUID(TAG_SEAT) ? tag.getUUID(TAG_SEAT) : null;
    }
}
