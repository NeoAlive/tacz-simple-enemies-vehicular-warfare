package com.neoalive.tacz_sewv.entity;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

/**
 * Invisible one-passenger mount for a sandbag fighting position. Not an SBW vehicle —
 * no gun/energy paths. Does not force rider yaw (unlocked look / body rotation).
 *
 * <p>While seated the rider receives a short, invisible {@link MobEffects#DAMAGE_RESISTANCE}
 * refresh each tick (cover from the bags) — no particles, no HUD icon.
 */
public class SandbagSeatEntity extends Entity {

    /**
     * Sit height above the block's bottom. Pose JSON is left alone — the Bedrock clip already
     * sinks the rig 5 model pixels; keep this at floor level so the figure settles into the bags
     * instead of hovering above the grass.
     */
    private static final double RIDER_Y = 0.0D;

    /** Refresh window — longer than a tick so brief hitch never drops the buff. */
    private static final int COVER_RESIST_TICKS = 40;
    /** Resistance I (20% damage). Amplifier 0. */
    private static final int COVER_RESIST_AMP = 0;

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
            return;
        }
        applyCoverResistance();
    }

    private void applyCoverResistance() {
        for (Entity passenger : this.getPassengers()) {
            if (!(passenger instanceof LivingEntity living)) continue;
            living.addEffect(new MobEffectInstance(
                    MobEffects.DAMAGE_RESISTANCE, COVER_RESIST_TICKS, COVER_RESIST_AMP,
                    false, false, false));
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
