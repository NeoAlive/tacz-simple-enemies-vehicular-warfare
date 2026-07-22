package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.List;

/**
 * Terrain sensing for {@link DriveVehicleGoal}: what a ground or ship hull must not drive
 * into. Water (unless it floats), lava, and the hulls a block probe cannot see — and, for a
 * hull that floats, dry land itself: see {@link #isLandHazard} for why running aground is a
 * dead end for a ship in a way it never is for a ground vehicle.
 *
 * <p><b>Depth is deliberately not a hazard.</b> SBW's fall damage on vehicles is forgiving,
 * so treating drops as cliffs cost far more mobility than it saved — armor would refuse
 * ravines, ledges and terraced ground it could simply have driven off. An uphill wall isn't
 * one either: footing is found immediately, and walls belong to the pathfinder and the
 * stuck recovery, not to a terrain probe. (A ship is the one exception — see
 * {@link #isLandHazard}, where depth below the surface is exactly what's being asked about.)
 */
final class GroundTerrainSensor extends TerrainSensor {

    /**
     * How far below the driving level the surface the hull would come to rest on is looked
     * for, purely to classify it as water or lava. A probe reach, NOT a fall limit — a drop
     * is allowed whether or not the bottom is within it.
     */
    private static final int FLUID_PROBE_DEPTH = 8;

    /** Positive buoyancy means it floats rather than sinking, so water stops being a hazard. */
    private boolean amphibious;

    GroundTerrainSensor(AbstractUnit unit) {
        super(unit);
    }

    @Override
    protected void onAttach(VehicleEntity v) {
        this.amphibious = computeAmphibious(v);
    }

    boolean enabled() {
        return SewvConfig.VEHICLE_TERRAIN_AVOIDANCE.get();
    }

    /** Read per call so config edits take effect live. */
    double lookahead() {
        return SewvConfig.VEHICLE_LOOKAHEAD_DISTANCE.get();
    }

    Vec3 chooseClearBearing(Vec3 desired) {
        return chooseClearBearing(desired, lookahead());
    }

    @Override
    boolean headingClear(Vec3 dir, double distance) {
        Level level = this.unit.level();
        double startX = this.vehicle.getX();
        double startZ = this.vehicle.getZ();
        int baseY = this.vehicle.getBlockY();
        double half = halfWidth();
        List<AABB> hulls = obstacles(distance);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // Step ~1 block at a time from just past the hull edge out to the look-ahead range.
        for (double d = half + 0.5; d <= half + distance; d += 1.0) {
            double sampleX = startX + dir.x * d;
            double sampleZ = startZ + dir.z * d;
            if (isBlockedByHull(hulls, sampleX, sampleZ)) return false;
            if (isHazardColumn(level, pos, Mth.floor(sampleX), Mth.floor(sampleZ), baseY)) return false;
        }
        return true;
    }

    /** A column is hazardous only if the surface the hull would end up on is water or lava. */
    private boolean isHazardColumn(Level level, BlockPos.MutableBlockPos pos, int x, int z, int baseY) {
        // A ship's hazard is the INVERSE of a ground hull's: dry land, not water. See
        // isLandHazard's own doc for why this matters more than it looks.
        if (this.amphibious) return isLandHazard(level, pos, x, z, baseY);

        // Fluid at the driving cell or the cell the tracks rest in.
        for (int dy = 0; dy >= -1; dy--) {
            if (isHazardFluid(level.getFluidState(pos.set(x, baseY + dy, z)))) return true;
        }

        // Keep scanning down to whatever the hull would actually land on and classify THAT
        // surface: a lake or lava pool at the bottom of a step-down still drowns or burns the
        // crew even though the fall itself is survivable. First solid block wins.
        for (int k = 1; k <= FLUID_PROBE_DEPTH; k++) {
            var state = level.getBlockState(pos.set(x, baseY - k, z));
            if (isHazardFluid(state.getFluidState())) return true;
            if (!state.getCollisionShape(level, pos).isEmpty()) return false; // solid footing
        }
        return false; // nothing but air within reach — a long drop, which is allowed
    }

    /**
     * Where the water gives out is a hazard for a ship, not a place to avoid becoming a place
     * it's fine to be: SuperbWarfare's own ship engine gates BOTH thrust and yaw change behind
     * {@code isInFluidType || isUnderWater} (verified against {@code VehicleEngineUtils.shipEngine}),
     * and {@code onGround()} separately clamps existing velocity toward zero. A hull that runs
     * fully aground therefore has no input that does anything at all — no thrust to back off
     * with, no yaw change to turn away with, because yaw change itself is scaled by CURRENT
     * SPEED, which grounding just crushed to zero. There is no "unstick" maneuver for a boat the
     * way there is for a wedged ground vehicle (reverse-and-swing needs exactly the speed a
     * beaching just took away) — this has to be prevented, not recovered from.
     *
     * <p>Scans down from the driving level (same {@link #FLUID_PROBE_DEPTH} used above) for the
     * first thing that ISN'T air: water means still afloat here (not a hazard), lava is always
     * one, and any other solid surface means the hull would run aground on it before ever
     * reaching water — a hazard regardless of how far down it is, unlike the ground-vehicle
     * case where a drop onto solid footing below a fluid layer is fine.
     */
    private boolean isLandHazard(Level level, BlockPos.MutableBlockPos pos, int x, int z, int baseY) {
        for (int dy = 0; dy >= -FLUID_PROBE_DEPTH; dy--) {
            var state = level.getBlockState(pos.set(x, baseY + dy, z));
            FluidState fluid = state.getFluidState();
            if (fluid.is(FluidTags.LAVA)) return true;
            if (fluid.is(FluidTags.WATER)) return false; // afloat here — not a hazard
            if (!state.getCollisionShape(level, pos).isEmpty()) return true; // solid ground, no water above it
        }
        return true; // no water anywhere in reach below the hull either
    }

    private boolean isHazardFluid(FluidState fluid) {
        return fluid.is(FluidTags.LAVA) || (fluid.is(FluidTags.WATER) && waterIsHazard());
    }

    /**
     * Water is something to keep out of, not something to be trapped by. Once the hull is
     * already in it, every probed bearing reads blocked, {@code chooseClearBearing} answers
     * null forever and {@link DriveVehicleGoal#holdAtEdge} pivots the hull in place for good —
     * the stuck recovery can't save it either, since rotation counts as progress. So a hull
     * that fell in stops treating water as a hazard and simply drives out toward its
     * destination.
     *
     * <p>ponytail: this drops the standoff wholesale while wet, so a hull that entered a lake
     * can cross it rather than hugging the shore. Bias the whiskers toward shallower water if
     * that ever matters.
     */
    private boolean waterIsHazard() {
        return !this.amphibious && !this.vehicle.isInWater();
    }

    /**
     * Wrecks (dead hulls linger as scenery) and allied crewed vehicles must not be driven
     * through. Enemy hulls stay fair game: the standoff ring already keeps the distance, and
     * refusing to close on an enemy "obstacle" would fight it.
     */
    @Override
    protected List<AABB> buildObstacles(double reach) {
        double half = halfWidth();
        double range = reach + half + 1.0;
        // ±2 vertically: an obstacle on a drivable slope still counts, one well above or
        // below is outside the band the hull can reach by driving.
        AABB search = this.vehicle.getBoundingBox().inflate(range, 2.0, range);
        return this.unit.level().getEntitiesOfClass(VehicleEntity.class, search,
                        v -> v != this.vehicle && isObstacle(v)).stream()
                .map(v -> v.getBoundingBox().inflate(half, 0.0, half))
                .toList();
    }

    private boolean isObstacle(VehicleEntity other) {
        if (other.isWreck()) return true;
        return other.getFirstPassenger() instanceof AbstractUnit driver
                && VehicleTargeting.isSameFaction(this.unit, driver);
    }

    private static boolean isBlockedByHull(List<AABB> obstacles, double x, double z) {
        for (AABB box : obstacles) {
            if (x >= box.minX && x <= box.maxX && z >= box.minZ && z <= box.maxZ) return true;
        }
        return false;
    }

    /** Ship engines float by construction. On any error, default to the safe answer: avoid water. */
    private static boolean computeAmphibious(VehicleEntity v) {
        try {
            EngineInfo engine = v.getEngineInfo();
            if (engine == null) return false;
            return engine instanceof EngineInfo.Ship || engine.getBuoyancy() > 0.0;
        } catch (Exception ignored) {}
        return false;
    }
}
