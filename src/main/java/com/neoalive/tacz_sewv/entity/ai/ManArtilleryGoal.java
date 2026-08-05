package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.ArtilleryEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.ChunkTicket;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.EnumSet;

/**
 * Lays and fires an artillery-class hull ({@link ArtilleryEntity}) on a fire-mission / radio
 * designation. Does not free-fight: without an aimpoint this goal is idle.
 */
public class ManArtilleryGoal extends Goal {

    private static final String WEAPON = "Main";
    private static final float AIM_TOLERANCE_DEG = 3.0F;
    private static final int CHUNK_REFRESH_TICKS = 60;
    private static final int SHOT_COOLDOWN_TICKS = 40;

    private final AbstractUnit unit;
    private final ChunkTicket hullChunk = new ChunkTicket();

    private long nextShotTime;
    private long nextChunkRefresh;

    public ManArtilleryGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        return ArtillerySupport.isCrewing(this.unit) && ArtillerySupport.hasFireWork(this.unit);
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void stop() {
        if (this.unit.getVehicle() instanceof VehicleEntity hull) {
            this.hullChunk.release(hull);
        }
    }

    @Override
    public void tick() {
        if (!(this.unit.getVehicle() instanceof ArtilleryEntity hull)) return;
        if (hull.isWreck()) return;

        maybeHoldChunk(hull);

        Vec3 aim = ArtillerySupport.aimpoint(this.unit);
        if (aim == null) return;

        LivingEntity shooter = this.unit;
        Vec3 launch = ArtillerySupport.solveAndLay(hull, shooter, aim);
        if (launch == null) return;
        if (ArtillerySupport.aimErrorDeg(hull, launch) > AIM_TOLERANCE_DEG) return;

        long now = this.unit.level().getGameTime();
        if (now < this.nextShotTime) return;

        try {
            if (!hull.canShoot(shooter)) return;
            hull.vehicleShoot(shooter, WEAPON);
            this.nextShotTime = now + SHOT_COOLDOWN_TICKS;
        } catch (Throwable ignored) {
            // Gun/ammo path unavailable — wait and retry.
        }
    }

    private void maybeHoldChunk(VehicleEntity hull) {
        if (!SewvConfig.ARTILLERY_CHUNK_LOADING.get()) {
            this.hullChunk.release(hull);
            return;
        }
        long now = this.unit.level().getGameTime();
        if (now < this.nextChunkRefresh) return;
        this.nextChunkRefresh = now + CHUNK_REFRESH_TICKS;
        this.hullChunk.follow(hull);
    }
}
