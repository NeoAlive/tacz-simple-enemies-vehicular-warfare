package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.debug.SewvDiag;
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

    private static final double LOOKAHEAD_DISTANCE = 5.0;

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
    private long lastBlockedDiagTick = Long.MIN_VALUE;
    /** Once-per-tick shoreline snapshot; also used to detect center-floor flicker. */
    private long lastShoreDiagTick = Long.MIN_VALUE;
    private int lastShoreFloor = Integer.MIN_VALUE + 1; // not a real probe result
    private static final int CENTER_HAZARD_DEBOUNCE_TICKS = 2;
    private int centerHazardTicks;
    private int lastStableCenterFloor = NO_SURFACE;
    /** Last post-debounce center-floor result fed into {@link #headingClear}. */
    private int lastEffectiveCenterFloor = NO_SURFACE;
    /**
     * Game time of the last {@link #centerHazardTicks} increment. Debounce is tick-scoped:
     * many {@code headingClear} calls in one tick must not advance the counter more than once.
     */
    private long lastCenterHazardIncrementTick = Long.MIN_VALUE;
    /** Per-tick cache of the hull-center column probe + debounced effective floor. */
    private long centerFloorCacheTick = Long.MIN_VALUE;
    private int centerFloorCacheColX;
    private int centerFloorCacheColZ;
    private int centerFloorCacheBaseY;
    private int cachedRawCenterFloor = NO_SURFACE;
    private int cachedEffectiveCenterFloor = NO_SURFACE;
    /** Set by each failed {@link #headingClear} call; consumed by the fan summary. */
    private String lastRejectReason = "unknown";
    private boolean lastFanHullDominated;
    private String lastFanReasons = "";

    GroundTerrainSensor(AbstractUnit unit) {
        super(unit);
    }

    @Override
    protected void onAttach(VehicleEntity v) {
        this.amphibious = computeAmphibious(v);
        this.climbHeight = Math.max(1, Mth.ceil(v.maxUpStep()));
        this.hullTop = Math.max(1, Mth.ceil(v.getBbHeight()) - 1);
        this.centerHazardTicks = 0;
        this.lastStableCenterFloor = NO_SURFACE;
        this.lastEffectiveCenterFloor = NO_SURFACE;
        this.lastCenterHazardIncrementTick = Long.MIN_VALUE;
        this.centerFloorCacheTick = Long.MIN_VALUE;
        this.lastFanHullDominated = false;
        this.lastFanReasons = "";
    }

    /**
     * True once the hull-center column is a sustained (post-debounce) fluid hazard while SBW still
     * says the hull is dry. That is the bank-overhang / water-lip case — not the wet escape hatch,
     * and not amphibious crossings.
     */
    boolean isDryBankLipHazard() {
        return this.vehicle != null
                && !this.amphibious
                && !this.vehicle.isInWater()
                && this.lastEffectiveCenterFloor == HAZARD;
    }

    /**
     * True when the most recent full-fan miss was hull-dominated: strictly more than half of the
     * failed offsets rejected with {@code reason=hull} ({@code hullCount * 2 > n}).
     */
    boolean isLastFanHullDominated() {
        return this.lastFanHullDominated;
    }

    String lastFanReasons() {
        return this.lastFanReasons;
    }

    boolean enabled() {
        return SewvConfig.VEHICLE_TERRAIN_AVOIDANCE.get();
    }

    /** Read per call so config edits take effect live. */
    double lookahead() {
        return LOOKAHEAD_DISTANCE;
    }

    Vec3 chooseClearBearing(Vec3 desired) {
        return chooseClearBearing(desired, lookahead());
    }

    /**
     * Ground fan with per-offset reject reasons. A full miss logs one summary so a frozen
     * preferred-bearing line cannot masquerade as "only one heading was tried".
     */
    @Override
    Vec3 chooseClearBearing(Vec3 desired, double probeDistance) {
        return chooseClearBearing(desired, probeDistance, false);
    }

    @Override
    Vec3 chooseClearBearing(Vec3 desired, double probeDistance, boolean stuck) {
        if (desired.lengthSqr() < 1.0E-8) return desired;
        this.lastFanHullDominated = false;
        this.lastFanReasons = "";
        StringBuilder reasons = new StringBuilder();
        int hullCount = 0;
        int n = 0;
        int end = stuck || isHardTurn(desired) ? WHISKER_OFFSETS_DEG.length : 1;
        for (int i = 0; i < end; i++) {
            double offDeg = WHISKER_OFFSETS_DEG[i];
            Vec3 candidate = VehicleTargeting.rotateY(desired, Math.toRadians(offDeg));
            this.lastRejectReason = "unknown";
            if (headingClear(candidate, probeDistance)) {
                this.lastFanHullDominated = false;
                this.lastFanReasons = "";
                return candidate;
            }
            if (n > 0) reasons.append(',');
            reasons.append(this.lastRejectReason);
            if ("hull".equals(this.lastRejectReason)) hullCount++;
            n++;
        }
        if (end < WHISKER_OFFSETS_DEG.length) {
            for (int i = 1; i < WHISKER_OFFSETS_DEG.length; i++) {
                double offDeg = WHISKER_OFFSETS_DEG[i];
                Vec3 candidate = VehicleTargeting.rotateY(desired, Math.toRadians(offDeg));
                this.lastRejectReason = "unknown";
                if (headingClear(candidate, probeDistance)) {
                    this.lastFanHullDominated = false;
                    this.lastFanReasons = "";
                    return candidate;
                }
                if (n > 0) reasons.append(',');
                reasons.append(this.lastRejectReason);
                if ("hull".equals(this.lastRejectReason)) hullCount++;
                n++;
            }
        }
        // Strictly more than half of failed offsets are hull: hullCount*2 > n.
        this.lastFanHullDominated = n > 0 && hullCount * 2 > n;
        this.lastFanReasons = reasons.toString();
        SewvDiag.pathingEvent(
                "fan BLOCKED unit={}#{} vehicle={}#{} offsets={} reasons=[{}] "
                        + "hull={}/{} hullDominated={} rule=hullCount*2>n desired={}",
                this.unit.getClass().getSimpleName(), this.unit.getId(),
                this.vehicle.getName().getString(), this.vehicle.getId(),
                n, this.lastFanReasons, hullCount, n, this.lastFanHullDominated, desired);
        return null;
    }

    private boolean isHardTurn(Vec3 desired) {
        return Math.abs(Math.toDegrees(VehicleTargeting.signedAngleTo(
                this.vehicle.getForwardDirection().normalize(), desired))) >= 25.0;
    }

    @Override
    boolean headingClear(Vec3 dir, double distance) {
        Level level = this.unit.level();
        double startX = this.vehicle.getX();
        double startZ = this.vehicle.getZ();
        int baseY = this.vehicle.getBlockY();
        int colX = Mth.floor(startX);
        int colZ = Mth.floor(startZ);
        double half = halfWidth();
        List<AABB> hulls = obstacles(distance);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        StringBuilder trace = new StringBuilder();

        // The footing under the hull itself, which the first sample is measured against.
        // Probed + debounced once per game tick (shared by every fan offset / facingClear /
        // retreat probe this tick) — see {@link #ensureCenterFloor}.
        ensureCenterFloor(level, pos, colX, colZ, baseY);
        int rawFloor = this.cachedRawCenterFloor;
        int floor = this.cachedEffectiveCenterFloor;
        if (SewvDiag.groundPathingVerbose()) {
            logShorelineCenter(level, pos, startX, startZ, colX, colZ, baseY, rawFloor, floor);
        }

        // Step ~1 block at a time from just past the hull edge out to the look-ahead range.
        boolean crossedDrop = false;
        for (double d = half + 0.5; d <= half + distance; d += 1.0) {
            double sampleX = startX + dir.x * d;
            double sampleZ = startZ + dir.z * d;
            if (isBlockedByHull(hulls, sampleX, sampleZ)) {
                this.lastRejectReason = "hull";
                trace.append(" hull@").append(Mth.floor(sampleX)).append(',').append(Mth.floor(sampleZ));
                logBlockedHeading(dir, distance, baseY, floor, "hull", trace);
                return false;
            }

            int surface = probeColumn(level, pos, Mth.floor(sampleX), Mth.floor(sampleZ), baseY);
            if (surface == HAZARD) {
                this.lastRejectReason = "fluid";
                trace.append(" hazard@").append(Mth.floor(sampleX)).append(',').append(Mth.floor(sampleZ));
                logBlockedHeading(dir, distance, baseY, floor, "fluid", trace);
                return false;
            }
            if (surface == NO_SURFACE) {
                trace.append(" drop@").append(Mth.floor(sampleX)).append(',').append(Mth.floor(sampleZ));
                crossedDrop = true;
                continue; // a drop: allowed, and nothing to measure a step against
            }
            if (crossedDrop) {
                trace.append(" land@").append(Mth.floor(sampleX)).append(',').append(Mth.floor(sampleZ));
                floor = surface;
                crossedDrop = false;
                continue;
            }
            if (floor != NO_SURFACE && floor != HAZARD && surface - floor > this.climbHeight) {
                this.lastRejectReason = "step";
                trace.append(" wall@").append(Mth.floor(sampleX)).append(',').append(Mth.floor(sampleZ))
                        .append(" step=").append(surface - floor);
                logBlockedHeading(dir, distance, baseY, floor, "step", trace);
                return false; // a wall
            }
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
     * Probe + debounce the hull-center column at most once per game tick for a given column.
     * Fan offsets, {@code facingClear}, and hull-fan retreat probes all share this result.
     */
    private void ensureCenterFloor(Level level, BlockPos.MutableBlockPos pos,
                                   int colX, int colZ, int baseY) {
        long now = level.getGameTime();
        if (now == this.centerFloorCacheTick
                && colX == this.centerFloorCacheColX
                && colZ == this.centerFloorCacheColZ
                && baseY == this.centerFloorCacheBaseY) {
            return;
        }
        int raw = probeColumn(level, pos, colX, colZ, baseY);
        int effective = stabilizeCenterFloor(raw, now);
        this.centerFloorCacheTick = now;
        this.centerFloorCacheColX = colX;
        this.centerFloorCacheColZ = colZ;
        this.centerFloorCacheBaseY = baseY;
        this.cachedRawCenterFloor = raw;
        this.cachedEffectiveCenterFloor = effective;
        this.lastEffectiveCenterFloor = effective;
    }

    /**
     * Debounce a dry-over-water center HAZARD across real game ticks. {@code centerHazardTicks}
     * increments at most once per {@code now} value, no matter how many callers share the
     * cached center probe within that tick.
     */
    private int stabilizeCenterFloor(int rawFloor, long now) {
        if (rawFloor == HAZARD && !this.vehicle.isInWater()) {
            if (now != this.lastCenterHazardIncrementTick) {
                this.centerHazardTicks++;
                this.lastCenterHazardIncrementTick = now;
                // Always log through the latch window (proves +1/gameTime); verbose after that.
                boolean latched = this.centerHazardTicks >= CENTER_HAZARD_DEBOUNCE_TICKS;
                if (this.centerHazardTicks <= CENTER_HAZARD_DEBOUNCE_TICKS
                        || SewvDiag.groundPathingVerbose()) {
                    SewvDiag.waterEvent(
                            "centerDebounce INC unit={}#{} vehicle={}#{} gameTime={} centerHazardTicks={} "
                                    + "threshold={} latched={}",
                            this.unit.getClass().getSimpleName(), this.unit.getId(),
                            this.vehicle.getName().getString(), this.vehicle.getId(),
                            now, this.centerHazardTicks, CENTER_HAZARD_DEBOUNCE_TICKS, latched);
                }
            }
            if (this.centerHazardTicks < CENTER_HAZARD_DEBOUNCE_TICKS) {
                return this.lastStableCenterFloor;
            }
            return HAZARD;
        }
        this.centerHazardTicks = 0;
        if (rawFloor != HAZARD) {
            this.lastStableCenterFloor = rawFloor;
        }
        return rawFloor;
    }

    private void logBlockedHeading(Vec3 dir, double distance, int baseY, int floor, String reason, StringBuilder trace) {
        if (!SewvDiag.groundPathingVerbose()) return;
        long now = this.unit.level().getGameTime();
        if (now == this.lastBlockedDiagTick) return;
        this.lastBlockedDiagTick = now;
        SewvDiag.pathing("headingClear BLOCKED unit={}#{} vehicle={}#{} reason={} dir={} distance={} baseY={} floor={} floorKind={} climbHeight={} amphibious={} inWater={} waterHazard={} trace={}",
                this.unit.getClass().getSimpleName(), this.unit.getId(),
                this.vehicle.getName().getString(), this.vehicle.getId(),
                reason, dir, distance, baseY, floor, floorKind(floor), this.climbHeight,
                this.amphibious, this.vehicle.isInWater(), waterIsHazard(), trace.toString().trim());
    }

    /**
     * Shoreline mismatch probe: raw fluid at the hull-center column vs SBW's {@code isInWater()}.
     * Once per game tick, and also whenever the center-floor classification flips (the flicker case).
     * Caller must already have checked {@link SewvDiag#groundPathingVerbose()}.
     */
    private void logShorelineCenter(Level level, BlockPos.MutableBlockPos pos,
                                    double startX, double startZ, int colX, int colZ,
                                    int baseY, int rawFloor, int effectiveFloor) {
        FluidState atBase = level.getFluidState(pos.set(colX, baseY, colZ));
        FluidState below = level.getFluidState(pos.set(colX, baseY - 1, colZ));
        boolean waterAtBase = atBase.is(FluidTags.WATER);
        boolean waterBelow = below.is(FluidTags.WATER);
        boolean lavaAtBase = atBase.is(FluidTags.LAVA);
        boolean lavaBelow = below.is(FluidTags.LAVA);
        boolean fluidNear = waterAtBase || waterBelow || lavaAtBase || lavaBelow;
        boolean floorFlipped = rawFloor != this.lastShoreFloor;
        boolean hazardInvolved = rawFloor == HAZARD || this.lastShoreFloor == HAZARD;
        long now = level.getGameTime();
        // Dry inland driving: stay quiet. Log at shoreline / when center floor flaps into or
        // out of HAZARD / once a tick while the center column is already HAZARD.
        if (!fluidNear && !hazardInvolved) return;
        if (now == this.lastShoreDiagTick && !floorFlipped) return;
        this.lastShoreDiagTick = now;
        this.lastShoreFloor = rawFloor;

        boolean inWater = this.vehicle.isInWater();
        boolean probeHazard = rawFloor == HAZARD;
        SewvDiag.water(
                "shoreCenter unit={}#{} vehicle={}#{} "
                        + "rawX={} rawZ={} floorX={} floorZ={} blockY={} "
                        + "fluidBaseY={} fluidBaseY-1={} "
                        + "waterAtBase={} waterBelow={} lavaAtBase={} lavaBelow={} "
                        + "probeFloor={} floorKind={} effectiveFloor={} effectiveKind={} "
                        + "probeHazard={} isInWater={} waterHazard={} amphibious={} mismatch={} centerHazardTicks={}",
                this.unit.getClass().getSimpleName(), this.unit.getId(),
                this.vehicle.getName().getString(), this.vehicle.getId(),
                startX, startZ, colX, colZ, baseY,
                fluidLabel(atBase), fluidLabel(below),
                waterAtBase, waterBelow, lavaAtBase, lavaBelow,
                rawFloor, floorKind(rawFloor), effectiveFloor, floorKind(effectiveFloor),
                probeHazard, inWater, waterIsHazard(), this.amphibious,
                probeHazard != inWater, this.centerHazardTicks);
    }

    private static String floorKind(int floor) {
        if (floor == HAZARD) return "HAZARD";
        if (floor == NO_SURFACE) return "NO_SURFACE";
        return "Y";
    }

    private static String fluidLabel(FluidState fluid) {
        if (fluid.is(FluidTags.WATER)) return "WATER";
        if (fluid.is(FluidTags.LAVA)) return "LAVA";
        if (fluid.isEmpty()) return "EMPTY";
        return fluid.getType().toString();
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
