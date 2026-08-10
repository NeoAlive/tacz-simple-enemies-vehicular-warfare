package com.neoalive.tacz_sewv.entity;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

/**
 * Invisible one-passenger mount for a sandbag fighting position. Not an SBW vehicle —
 * no gun/energy paths. Does not force rider yaw (unlocked look / body rotation).
 */
public class SandbagSeatEntity extends Entity {

    /**
     * Sit height above the block's bottom. The pose itself sinks the rig 5 model pixels
     * ({@code unit} position in {@code sandbag_seat.animation.json}), so this cancels that and
     * lands the figure's base on the block's floor. Tune here, not in the animation, if the
     * rider ends up too high or clipped into the ground.
     */
    private static final double RIDER_Y = 5.0D / 16.0D;

    private BlockPos sandbagPos = BlockPos.ZERO;

    public SandbagSeatEntity(EntityType<? extends SandbagSeatEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public void bindTo(BlockPos pos, Direction facing) {
        this.sandbagPos = pos.immutable();
        // Blockbench seat group origin (7, 0, 0) → prefer block centre; facing only for spawn yaw.
        Vec3 at = Vec3.atBottomCenterOf(pos).add(0.0D, RIDER_Y, 0.0D);
        this.setPos(at.x, at.y, at.z);
        this.setYRot(facing.toYRot());
        this.yRotO = this.getYRot();
    }

    public BlockPos getSandbagPos() {
        return this.sandbagPos;
    }

    public boolean tryMount(LivingEntity rider) {
        if (!this.getPassengers().isEmpty()) return false;
        return rider.startRiding(this, true);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) return;
        if (this.sandbagPos == BlockPos.ZERO) {
            this.discard();
            return;
        }
        if (!this.level().getBlockState(this.sandbagPos).is(
                com.neoalive.tacz_sewv.init.ModBlocks.SANDBAG.get())) {
            this.ejectPassengers();
            this.discard();
        }
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return this.getPassengers().isEmpty() && passenger instanceof LivingEntity;
    }

    @Override
    public double getPassengersRidingOffset() {
        return 0.0D;
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction move) {
        if (!this.hasPassenger(passenger)) return;
        // Keep XZ on the seat; do not overwrite passenger yaw.
        move.accept(passenger, this.getX(), this.getY(), this.getZ());
    }

    @Override
    public boolean shouldRiderSit() {
        return true;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    protected void defineSynchedData() {}

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("SandbagX")) {
            this.sandbagPos = new BlockPos(tag.getInt("SandbagX"), tag.getInt("SandbagY"),
                    tag.getInt("SandbagZ"));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("SandbagX", this.sandbagPos.getX());
        tag.putInt("SandbagY", this.sandbagPos.getY());
        tag.putInt("SandbagZ", this.sandbagPos.getZ());
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        Entity first = this.getFirstPassenger();
        return first instanceof LivingEntity living ? living : null;
    }
}
