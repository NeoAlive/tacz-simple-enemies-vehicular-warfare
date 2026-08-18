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
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import com.neoalive.tacz_sewv.airport.AirportClearance;
import com.neoalive.tacz_sewv.airport.AirportRegistry;
import com.neoalive.tacz_sewv.client.AirportClient;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.init.ModBlockEntities;

/**
 * Player-defined PMC strip: two corners, a cleared flag, and the cached (touchdown, heading)
 * that {@link com.neoalive.tacz_sewv.entity.ai.goal.DrivePlaneGoal} already knows how to fly.
 */
public class RunwayBlockEntity extends BlockEntity implements GeoBlockEntity {

    private static final RawAnimation SPIN =
            RawAnimation.begin().thenLoop("animation.runway_block.spin");
    /**
     * The mast tops out 2.6 blocks up, and the dish sweeps a 9.7px radius around a pivot that is
     * not the block centre, so it reaches to x=1.45 / z=-0.45 as it turns. A default 1×1×1 box
     * would frustum-cull it while you were still looking at it.
     */
    private static final AABB RENDER_EXTENT = new AABB(-0.5, 0.0, -0.5, 1.5, 2.75, 1.0);

    private int x1;
    private int z1;
    private int x2;
    private int z2;
    private boolean cleared;
    @Nullable private BlockPos touchdown;
    private float headingDeg;
    @Nullable private BlockPos threshold;
    private int length;
    private int width;
    /**
     * Segmentation settings, per runway rather than per world. They are a statement about what
     * this strip is for — a forward field packing in six light aircraft wants nothing like the
     * spacing of a bomber base — so a single global number would be wrong for every airport but
     * one. The config values are only the starting point a new runway block is created with.
     */
    private double slotFactor = SewvConfig.AIRPORT_SLOT_SIZE_FACTOR.get();
    private double bufferFactor = SewvConfig.AIRPORT_SLOT_BUFFER_FACTOR.get();
    private double extraFactor = SewvConfig.AIRPORT_EXTRA_TAKEOFF_FACTOR.get();
    /** Derived from the four numbers above; rebuilt whenever they change, never saved. */
    @Nullable private AirportRegistry.Airport airport;
    /**
     * {@link #onChunkUnloaded()} runs before {@link #setRemoved()} when the chunk goes away, and
     * does not run on a real break. That is the only way {@code setRemoved} can tell the two
     * apart — and it must, because the client never sees {@link RunwayBlock#onRemove}.
     */
    private boolean chunkUnloaded;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public RunwayBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RUNWAY.get(), pos, state);
        this.x1 = pos.getX();
        this.z1 = pos.getZ();
        this.x2 = pos.getX();
        this.z2 = pos.getZ();
    }

    public int getX1() { return x1; }
    public int getZ1() { return z1; }
    public int getX2() { return x2; }
    public int getZ2() { return z2; }
    public boolean isCleared() { return cleared; }
    @Nullable public BlockPos getTouchdown() { return touchdown; }
    public float getHeadingDeg() { return headingDeg; }
    /** Centre of the landing end. Slot 0 starts here and the takeoff run is at the far end. */
    @Nullable public BlockPos getThreshold() { return threshold; }
    public int getLength() { return length; }
    public int getWidth() { return width; }
    public double getSlotFactor() { return slotFactor; }
    public double getBufferFactor() { return bufferFactor; }
    public double getExtraFactor() { return extraFactor; }

    public void setFactors(double slotFactor, double bufferFactor, double extraFactor) {
        this.slotFactor = slotFactor;
        this.bufferFactor = bufferFactor;
        this.extraFactor = extraFactor;
        this.airport = null;
        setChanged();
    }

    /**
     * Check Clearance has stored a flyable strip. The radar dish spins only then — an uncleared
     * marker is a metal cube with a parked antenna.
     */
    public boolean hasCachedAirport() {
        return cleared && threshold != null && length > 0;
    }

    /** The cached strip: geometry plus its parking slots. Null while it is not cleared. */
    @Nullable
    public AirportRegistry.Airport airport() {
        if (airport == null && cleared && threshold != null) {
            airport = AirportRegistry.Airport.of(threshold, headingDeg, length, width,
                    slotFactor, bufferFactor, extraFactor);
        }
        return airport;
    }

    public BlockPos corner1() {
        return new BlockPos(x1, worldPosition.getY(), z1);
    }

    public BlockPos corner2() {
        return new BlockPos(x2, worldPosition.getY(), z2);
    }

    public void setCorners(int x1, int z1, int x2, int z2) {
        this.x1 = x1;
        this.z1 = z1;
        this.x2 = x2;
        this.z2 = z2;
        setChanged();
    }

    public void applyClearance(AirportClearance.Result result) {
        this.cleared = true;
        this.touchdown = result.touchdown() == null ? null : result.touchdown().immutable();
        this.headingDeg = result.headingDeg();
        this.threshold = result.threshold().immutable();
        this.length = result.length();
        this.width = result.width();
        this.airport = null;
        if (level instanceof ServerLevel serverLevel) {
            AirportRegistry.get(serverLevel).note(worldPosition, airport());
        }
        syncClient();
    }

    public void clearClearance() {
        this.cleared = false;
        this.touchdown = null;
        this.headingDeg = 0.0F;
        this.threshold = null;
        this.length = 0;
        this.width = 0;
        this.airport = null;
        if (level instanceof ServerLevel serverLevel) {
            AirportRegistry.get(serverLevel).forget(worldPosition);
        }
        syncClient();
    }

    private void syncClient() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
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
    public AABB getRenderBoundingBox() {
        return RENDER_EXTENT.move(worldPosition);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "spin", 0, this::spinPredicate));
    }

    private PlayState spinPredicate(AnimationState<RunwayBlockEntity> state) {
        // Evaluated from the BER, so a culled dish does not tick the spin — and an uncleared
        // strip never starts it in the first place.
        if (!hasCachedAirport()) return PlayState.STOP;
        return state.setAndContinue(SPIN);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel && airport() != null) {
            AirportRegistry.get(serverLevel).note(worldPosition, airport());
        }
    }

    @Override
    public void onChunkUnloaded() {
        this.chunkUnloaded = true;
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        // Real break/replace only. Walking away must leave the registry (and the map plot) so a
        // land order can still resolve a strip whose chunk is not loaded.
        if (!this.chunkUnloaded) {
            dropAirportCache();
        }
        super.setRemoved();
    }

    /** Forget the derived strip, the dimension registry, and the client map plot. */
    private void dropAirportCache() {
        this.airport = null;
        if (level instanceof ServerLevel serverLevel) {
            AirportRegistry.get(serverLevel).forget(worldPosition);
        } else if (level != null && level.isClientSide) {
            AirportClient.forgetPlot(worldPosition);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("X1", x1);
        tag.putInt("Z1", z1);
        tag.putInt("X2", x2);
        tag.putInt("Z2", z2);
        tag.putBoolean("Cleared", cleared);
        tag.putFloat("Heading", headingDeg);
        tag.putInt("Length", length);
        tag.putInt("Width", width);
        tag.putDouble("SlotFactor", slotFactor);
        tag.putDouble("BufferFactor", bufferFactor);
        tag.putDouble("ExtraFactor", extraFactor);
        if (touchdown != null) tag.putLong("Touchdown", touchdown.asLong());
        if (threshold != null) tag.putLong("Threshold", threshold.asLong());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        x1 = tag.getInt("X1");
        z1 = tag.getInt("Z1");
        x2 = tag.getInt("X2");
        z2 = tag.getInt("Z2");
        cleared = tag.getBoolean("Cleared");
        headingDeg = tag.getFloat("Heading");
        length = tag.getInt("Length");
        width = tag.getInt("Width");
        if (tag.contains("SlotFactor")) slotFactor = tag.getDouble("SlotFactor");
        if (tag.contains("BufferFactor")) bufferFactor = tag.getDouble("BufferFactor");
        if (tag.contains("ExtraFactor")) extraFactor = tag.getDouble("ExtraFactor");
        touchdown = tag.contains("Touchdown") ? BlockPos.of(tag.getLong("Touchdown")) : null;
        threshold = tag.contains("Threshold") ? BlockPos.of(tag.getLong("Threshold")) : null;
        airport = null;
        // A strip cleared before the strip was measured has no geometry to segment; make the
        // player press Check Clearance again rather than fly an approach built from zeroes.
        if (cleared && (threshold == null || length <= 0)) cleared = false;
    }
}
