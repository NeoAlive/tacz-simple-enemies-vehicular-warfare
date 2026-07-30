package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.ChunkTicket;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Works an FCP mortar vehicle from the gunner seat: reload the magazine, wait for the tube
 * to settle on a TrajectoryCalculator solution, fire.
 *
 * <p>Live-target aiming is owned by {@link com.neoalive.tacz_sewv.mixin.MixinVehicleMissileAim}
 * on <b>both</b> sides (SBW turret angles are not networked — a server-only aim left the
 * client tube frozen). This goal still lays for fire-mission marks (no entity UUID for that
 * mixin path) and owns the trigger: a lofted mortar never passes SBW's look-angle gate.
 *
 * <p>Chunk loading reuses {@link SewvConfig#MORTAR_CHUNK_LOADING}: the gunner sits in the
 * hull, so one ticket on the vehicle is enough (unlike Fixed mortars, where crew and tube
 * can straddle a chunk boundary).
 */
public class ManVehicleMortarGoal extends Goal {

    private static final float AIM_TOLERANCE_DEG = 2.0F;
    private static final double RE_AIM_DISTANCE_SQ = 4.0 * 4.0;

    private final AbstractUnit unit;
    private VehicleEntity hull;

    private final ChunkTicket hullChunk = new ChunkTicket();

    /** Game time before the next shot may leave the tube. */
    private long nextShotTime;

    /** Where the barrel is laid, or null if it needs laying. */
    private BlockPos laidOn;

    /** Launch vector last demanded (settle test + fire-mission aim). */
    private Vec3 laidLaunch;

    /** Aimpoint used for the current lay (for vehicleShoot targetPos). */
    private Vec3 laidAim;

    public ManVehicleMortarGoal(AbstractUnit unit) {
        this.unit = unit;
        // Claims nothing. The gunner is seated; SBW owns the seat, this only reloads / lays /
        // fires. LOOK would contend with nothing useful and MOVE would fight the driver.
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        // Fire-mission lay and settle checks need every game tick; half-rate made the tube
        // feel stuck and widened the window where TurretGunnerGoal could steal a flat shot.
        return true;
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (!VehicleMortarSupport.isCrewing(this.unit)) return false;
        if (!(this.unit.getVehicle() instanceof VehicleEntity v) || v.isWreck()) return false;
        this.hull = v;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.hull != null
                && this.unit.getVehicle() == this.hull
                && !this.hull.isWreck()
                && VehicleMortarSupport.isCrewing(this.unit);
    }

    @Override
    public void stop() {
        releaseForcedChunks();
        this.hull = null;
        this.laidOn = null;
        this.laidLaunch = null;
        this.laidAim = null;
    }

    @Override
    public void tick() {
        updateChunkLoading();
        VehicleMortarSupport.reload(this.hull, this.unit);

        Vec3 aimCentre = VehicleMortarSupport.aimpoint(this.unit);
        if (aimCentre == null) {
            this.laidOn = null;
            this.laidLaunch = null;
            this.laidAim = null;
            return;
        }

        BlockPos aimPos = BlockPos.containing(aimCentre);
        if (this.laidOn != null && this.laidOn.distSqr(aimPos) > RE_AIM_DISTANCE_SQ) {
            this.laidOn = null;
            this.laidLaunch = null;
            this.laidAim = null;
        }

        LivingEntity target = this.unit.getTarget();
        boolean liveTarget = target != null && target.isAlive();

        if (this.laidOn == null) {
            // Live-target lay must match MixinVehicleMissileAim (exact centre, both sides).
            // Scatter only offsets the shell's targetPos, not the tube demand — otherwise the
            // mixin and the settle test fight and the gunner never fires.
            Vec3 launch = VehicleMortarSupport.solveAim(this.hull, this.unit, aimCentre);
            if (launch == null) return;

            Vec3 impact = MortarSupport.scatter(aimCentre, this.unit.getRandom());

            // Fire-mission marks have no UUID for the mixin path — lay here on the server.
            if (!liveTarget) {
                VehicleMortarSupport.faceLaunch(this.unit, launch);
                VehicleMortarSupport.aimAt(this.hull, launch);
            }
            this.laidOn = aimPos;
            this.laidLaunch = launch;
            this.laidAim = impact;
            return;
        }

        // Refresh the demanded launch each tick so a moving target keeps the settle test honest
        // with the mixin's continuous re-solve.
        Vec3 launch = VehicleMortarSupport.solveAim(this.hull, this.unit, aimCentre);
        if (launch == null) return;
        this.laidLaunch = launch;

        if (!liveTarget) {
            VehicleMortarSupport.faceLaunch(this.unit, this.laidLaunch);
            VehicleMortarSupport.aimAt(this.hull, this.laidLaunch);
        }

        if (this.unit.level().getGameTime() < this.nextShotTime) return;
        if (!VehicleMortarSupport.aimSettled(this.hull, this.unit, this.laidLaunch, AIM_TOLERANCE_DEG)) {
            return;
        }
        if (!this.hull.canShoot(this.unit)) return;

        UUID targetId = liveTarget ? target.getUUID() : this.unit.getUUID();
        this.hull.vehicleShoot(this.unit, targetId, this.laidAim);

        this.nextShotTime = this.unit.level().getGameTime() + SewvConfig.MORTAR_FIRE_COOLDOWN_TICKS.get();
        this.laidOn = null;
        this.laidLaunch = null;
        this.laidAim = null;
    }

    /**
     * Holds the hull's chunk so a remote fire mission keeps working with no player nearby.
     * Same config gate as Fixed {@link ManMortarGoal}; one ticket is enough because the
     * gunner is a passenger of the hull.
     */
    private void updateChunkLoading() {
        if (SewvConfig.MORTAR_CHUNK_LOADING.get()) {
            this.hullChunk.follow(this.hull);
        } else {
            releaseForcedChunks();
        }
    }

    private void releaseForcedChunks() {
        this.hullChunk.release(this.hull);
    }
}
