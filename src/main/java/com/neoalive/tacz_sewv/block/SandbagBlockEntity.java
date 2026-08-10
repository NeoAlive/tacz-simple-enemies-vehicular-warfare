package com.neoalive.tacz_sewv.block;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import com.neoalive.tacz_sewv.entity.SandbagSeatEntity;
import com.neoalive.tacz_sewv.init.ModBlockEntities;
import com.neoalive.tacz_sewv.init.ModEntities;

/**
 * Seat entity + soft ENTRENCHED claim for one sandbag. Claim is O(1) UUID storage — not a
 * world entity scan — so assign / availability checks stay cheap on busy maps. A dead or
 * missing claimant is cleared on read (same self-heal shape as a mortar claim).
 */
public class SandbagBlockEntity extends BlockEntity {

    private static final String TAG_SEAT = "SeatUUID";
    private static final String TAG_CLAIMANT = "ClaimantUUID";

    @Nullable
    private UUID seatId;
    @Nullable
    private UUID claimantId;

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

    /** Existing seat only — does not spawn one. Used for occupancy checks. */
    @Nullable
    public SandbagSeatEntity getSeat(ServerLevel level) {
        return findSeat(level);
    }

    public void discardSeat(ServerLevel level) {
        SandbagSeatEntity seat = findSeat(level);
        if (seat != null) {
            seat.ejectPassengers();
            seat.discard();
        }
        this.seatId = null;
        this.claimantId = null;
        setChanged();
    }

    public void setClaimant(@Nullable LivingEntity unit) {
        UUID next = unit == null ? null : unit.getUUID();
        if (next == null && this.claimantId == null) return;
        if (next != null && next.equals(this.claimantId)) return;
        this.claimantId = next;
        setChanged();
    }

    public void clearClaimantIf(@Nullable LivingEntity unit) {
        if (unit == null || this.claimantId == null) return;
        if (this.claimantId.equals(unit.getUUID())) {
            this.claimantId = null;
            setChanged();
        }
    }

    @Nullable
    public UUID getClaimantId() {
        return this.claimantId;
    }

    /**
     * True when nobody holds a live soft claim, or {@code self} is the claimant.
     * Dead / discarded / unloaded holders drop the claim here so seats cannot lock forever.
     */
    public boolean isClaimAvailable(@Nullable LivingEntity self) {
        if (this.claimantId == null) return true;
        if (self != null && this.claimantId.equals(self.getUUID())) return true;
        if (!(this.level instanceof ServerLevel server)) return false;
        Entity holder = server.getEntity(this.claimantId);
        if (holder == null || !holder.isAlive()) {
            this.claimantId = null;
            setChanged();
            return true;
        }
        return false;
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
        if (this.claimantId != null) {
            tag.putUUID(TAG_CLAIMANT, this.claimantId);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.seatId = tag.hasUUID(TAG_SEAT) ? tag.getUUID(TAG_SEAT) : null;
        this.claimantId = tag.hasUUID(TAG_CLAIMANT) ? tag.getUUID(TAG_CLAIMANT) : null;
    }
}
