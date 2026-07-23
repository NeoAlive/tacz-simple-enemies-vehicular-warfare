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
 * Terrain sensing for {@link DriveVehicleGoal}: what a ground hull must not drive into. Water
 * (unless it floats), lava, and the hulls a block probe cannot see. Ships have their own
 * ({@link ShipTerrainSensor}), which inverts the water rule rather than relaxing it.
 *
 * <p>The {@code amphibious} flag here only ever RELAXES the water hazard, for a buoyant ground
 * hull that can swim a crossing. It must never be read as "this is a boat" — an amphibious APC
 * still drives on land, so land is never a hazard in this sensor.
 *
 * <p><b>Depth is deliberately not a hazard.</b> SBW's fall damage on vehicles is forgiving,
 * so treating drops as cliffs cost far more mobility than it saved — armor would refuse
 * ravines, ledges and terraced ground it could simply have driven off.
 *
 * <p><b>Height is, though.</b> Leaving walls entirely to the pathfinder was the original
 * design and it does not hold up: a hull cutting a corner, driving a stale path, or steering
 * off its path to hold a standoff has nothing telling it a house is four blocks ahead, and it
 * simply drives into it. The rule cannot be "any solid block ahead" — terrain rises, and a
 * sensor that refuses slopes is the mobility loss this class already learned to avoid once. So
 * what is measured is the <b>step between consecutive samples</b>: ground that climbs a block
 * at a time is a hill and is fine, while ground that jumps by more than the hull can mount is a
 * wall, a tree trunk or a cliff face.
 */
final class GroundTerrainSensor extends TerrainSensor {

    /**
     * How far below the driving level the surface the hull would come to rest on is looked
     * for, purely to classify it as water or lava. A probe reach, NOT a fall limit — a drop
     * is allowed whether or not the bottom is within it.
     */
    private static final int FLUID_PROBE_DEPTH = 8;

    /** {@link #probeColumn} answers: nothing solid within reach (a drop), or a hazard fluid. */
    private static final int NO_SURFACE = Integer.MIN_VALUE;
    private static final int HAZARD = Integer.MAX_VALUE;

    /** Positive buoyancy means it floats rather than sinking, so water stops being a hazard. */
    private boolean amphibious;

    /**
     * The tallest step this hull can drive up, in whole blocks. Read from the hull's own
     * {@code maxUpStep} so a vehicle built to climb better is allowed to, rather than every hull
     * sharing one guess; floored at 1 because a hull that officially steps less than a block still
     * manages kerbs, and treating those as walls would be the refuse-everything failure again.
     */
    private int climbHeight = 1;

    /**
     * How far above its base the hull physically occupies, and therefore how high a block still
     * counts as being in the way. Anything above this — an overhanging canopy, a bridge deck — is
     * driven under, which is why the probe does not simply scan from the sky down.
     */
    private int hullTop = 1;

    GroundTerrainSensor(AbstractUnit unit) {
        super(unit);
    }

    @Override
    protected void onAttach(VehicleEntity v) {
        this.amphibious = computeAmphibious(v);
        this.climbHeight = Math.max(1, Mth.ceil(v.maxUpStep()));
        this.hullTop = Math.max(1, Mth.ceil(v.getBbHeight()) - 1);
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

        // The footing under the hull itself, which the first sample is measured against.
        int floor = probeColumn(level, pos, Mth.floor(startX), Mth.floor(startZ), baseY);

        // Step ~1 block at a time from just past the hull edge out to the look-ahead range.
        for (double d = half + 0.5; d <= half + distance; d += 1.0) {
            double sampleX = startX + dir.x * d;
            double sampleZ = startZ + dir.z * d;
            if (isBlockedByHull(hulls, sampleX, sampleZ)) return false;

            int surface = probeColumn(level, pos, Mth.floor(sampleX), Mth.floor(sampleZ), baseY);
            if (surface == HAZARD) return false;
            if (surface == NO_SURFACE) continue; // a drop: allowed, and nothing to measure a step against
            if (floor != NO_SURFACE && surface - floor > this.climbHeight) return false; // a wall
            floor = surface;
        }
        return true;
    }

    /**
     * What this column offers the hull: {@link #HAZARD} if driving onto it would drown or burn the
     * crew, {@link #NO_SURFACE} if there is nothing within reach (a drop), otherwise the Y of the
     * footing it would ride on.
     *
     * <p>Scanned from the top of the hull's own volume downward, so the first thing found is what
     * the hull would actually hit — a wall at chest height is reported as footing far above the
     * ground it stands on, which is exactly what makes the step test see it as a wall.
     */
    private int probeColumn(Level level, BlockPos.MutableBlockPos pos, int x, int z, int baseY) {
        // Fluid at the driving cell or the cell the tracks rest in, checked before anything else:
        // a hazard hidden under an overhang is still a hazard.
        for (int dy = 0; dy >= -1; dy--) {
            if (isHazardFluid(level.getFluidState(pos.set(x, baseY + dy, z)))) return HAZARD;
        }

        // Down past whatever the hull would actually land on and classify THAT surface: a lake or
        // lava pool at the bottom of a step-down still drowns or burns the crew even though the
        // fall itself is survivable.
        for (int y = baseY + this.hullTop; y >= baseY - FLUID_PROBE_DEPTH; y--) {
            var state = level.getBlockState(pos.set(x, y, z));
            if (isHazardFluid(state.getFluidState())) return HAZARD;
            if (!state.getCollisionShape(level, pos).isEmpty()) return y; // solid footing, or a wall
            // A hull that floats rides the water surface, so for it that IS the footing — without
            // this the probe reports the lake bed and the far bank reads as a wall to climb out of.
            if (this.amphibious && state.getFluidState().is(FluidTags.WATER)) return y;
        }
        return NO_SURFACE; // nothing but air within reach — a long drop, which is allowed
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
