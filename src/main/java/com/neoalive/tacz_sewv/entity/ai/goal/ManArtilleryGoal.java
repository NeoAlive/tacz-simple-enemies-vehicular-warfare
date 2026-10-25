package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import com.atsuishio.superbwarfare.entity.vehicle.base.ArtilleryEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.config.EasyMode;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.ArtillerySupport;
import com.neoalive.tacz_sewv.util.ChunkTicket;
import com.neoalive.tacz_sewv.util.ImpactTickets;

/**
 * Fires an artillery-class hull ({@link ArtilleryEntity}) on a fire-mission / radio designation,
 * once the real barrel is within {@code artilleryAimToleranceDeg} of the solution. It does not lay
 * the gun — {@code MixinArtilleryShootVec} + {@code MixinVehicleMissileAim} do — and it does not
 * free-fight: without an aimpoint this goal is idle.
 */
public class ManArtilleryGoal extends Goal {

    private static final int CHUNK_REFRESH_TICKS = 60;
    private static final int SHOT_COOLDOWN_TICKS = 40;
    /** Past each shot; a 45° lob at 1000 blocks flies ~183 ticks. */
    private static final long IMPACT_HOLD_TICKS = 400L;

    private final AbstractUnit unit;
    private final ChunkTicket hullChunk = new ChunkTicket(ChunkTicket.Kind.ARTILLERY);

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

        // Re-solved every tick from the live aimpoint; the mixins lay the gun off the same solve.
        // The gate is the REAL barrel, never shootVec (which is just the demand).
        LivingEntity shooter = this.unit;
        Vec3 launch = ArtillerySupport.solve(hull, shooter, aim);
        if (launch == null) {
            ArtillerySupport.debugLine(hull, this.unit, aim, null, "N:nosolution");
            return;
        }
        double tolerance = SewvConfig.ARTILLERY_AIM_TOLERANCE_DEG.get();
        if (ArtillerySupport.angleDeg(hull.getBarrelVector(1.0F), launch) > tolerance) {
            ArtillerySupport.debugLine(hull, this.unit, aim, launch, "N:aim");
            return;
        }

        long now = this.unit.level().getGameTime();
        if (now < this.nextShotTime) {
            ArtillerySupport.debugLine(hull, this.unit, aim, launch, "N:cooldown");
            return;
        }

        try {
            if (!hull.canShoot(shooter)) {
                ArtillerySupport.debugLine(hull, this.unit, aim, launch, "N:canShoot");
                return;
            }
            // Third argument is a target POSITION (missile guidance), not a direction.
            hull.vehicleShoot(shooter, ArtillerySupport.weaponOf(hull, shooter), aim);
            holdImpactChunk(aim, now);
            this.nextShotTime = now + EasyMode.crewServedTicks(this.unit, SHOT_COOLDOWN_TICKS);
            ArtillerySupport.debugLine(hull, this.unit, aim, launch, "Y");
        } catch (Throwable ignored) {
            // Gun/ammo path unavailable — wait and retry.
            ArtillerySupport.debugLine(hull, this.unit, aim, launch, "N:error");
        }
    }

    /**
     * Keeps the far end ticking for as long as the gun fires, not just for the radio call's window:
     * a shell flying into an unloaded chunk freezes there, and after a reboot the call's own hold
     * is gone while the (persisted) mission is not.
     */
    private void holdImpactChunk(Vec3 aim, long now) {
        if (!SewvConfig.ARTILLERY_CHUNK_LOADING.get()) return;
        if (!(this.unit.level() instanceof ServerLevel level)) return;
        ImpactTickets.hold(level, new ChunkPos(BlockPos.containing(aim)), now + IMPACT_HOLD_TICKS);
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
