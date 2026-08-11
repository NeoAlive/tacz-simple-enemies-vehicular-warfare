package com.neoalive.tacz_sewv.entity.ai.plane;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.entity.ai.sensor.AirTerrainSensor;

/**
 * What the ground ahead is doing, for an aircraft that cannot stop to think about it.
 *
 * <p>Three separate questions, deliberately not merged, because each failed differently in play:
 *
 * <ul>
 * <li><b>Is the next second of flight clear?</b> {@link #probeDistance} scales the block whisker
 *     with airspeed. It used to be a flat 48 blocks, which a jet at cruise crosses in well under a
 *     second — the probe reported clear, and the aircraft hit the thing it had just cleared.</li>
 * <li><b>Where should the course go?</b> {@link #corridorBearing} is a two-deep best-first search
 *     over heading offsets on the heightmap. One-deep (the old escape fan) happily turns into a
 *     bowl or a blind valley, because every bearing out of it looks clear until you are in it.</li>
 * <li><b>Is this dive survivable?</b> {@link #diveSafe} integrates the actual descent against the
 *     ground under it before the aircraft commits. The old code only checked height under the nose
 *     each tick and pulled up when it was already too late.</li>
 * </ul>
 *
 * <p>Terrain is read from {@link Heightmap.Types#WORLD_SURFACE}; blocks are the whisker's job. This
 * class answers questions and never steers.
 */
public final class PlaneTerrain {

    /** Whisker reach: this many ticks of travel ahead, floored and capped. */
    private static final double PROBE_LOOKAHEAD_TICKS = 30.0;
    private static final double PROBE_MIN = 32.0;
    private static final double PROBE_MAX = 96.0;

    /** Heading offsets the corridor search may consider, nearest deflection first. */
    private static final double[] FAN_DEG = {
            0.0, 15.0, -15.0, 30.0, -30.0, 45.0, -45.0, 60.0, -60.0, 75.0, -75.0, 90.0, -90.0
    };
    private static final double SAMPLE_STEP = 12.0;
    /** Vertical room a leg must leave under the hold altitude to count as clear. */
    private static final double CLEAR_MARGIN = 8.0;
    /** How many of the clear legs get their continuation checked. Bounds the cost of depth two. */
    private static final int EXPAND_BEST = 4;
    /** Cost added to a leg whose continuation is blocked — flyable now, a dead end shortly. */
    private static final double DEAD_END_COST = 120.0;
    private static final int CACHE_TTL_TICKS = 20;
    /** Whisker TTL: shorter than the corridor — traffic moves, terrain does not. */
    private static final int WHISKER_CACHE_TTL_TICKS = 8;
    /** Heading buckets for the whisker cache (~5°), matching the corridor quantiser. */
    private static final double WHISKER_HEADING_BUCKET_DEG = 5.0;
    /** Speed buckets so a climb/dive that changes airspeed refreshes the reach. */
    private static final double WHISKER_SPEED_BUCKET = 0.5;

    /** Steps the dive integrator takes along the run before it trusts the geometry. */
    private static final int DIVE_SAMPLES = 12;

    // Corridor cache — one goal per pilot, so instance state is per aircraft by construction.
    private long cacheTick = Long.MIN_VALUE;
    private int cacheHeadingQ;
    private int cacheClearY;
    private Vec3 cacheDir;
    private boolean cacheValid;

    // Whisker cache — holdAbout used to pay a full fan every tick for an orbit that barely turns.
    private long whiskerCacheTick = Long.MIN_VALUE;
    private int whiskerHeadingQ;
    private int whiskerSpeedQ;
    private Vec3 whiskerCacheDir;
    private boolean whiskerCacheValid;

    /** Drop the cached corridor and whisker — a new hull, or a deliberate re-plan. */
    public void clear() {
        this.cacheValid = false;
        this.cacheDir = null;
        this.cacheTick = Long.MIN_VALUE;
        this.whiskerCacheValid = false;
        this.whiskerCacheDir = null;
        this.whiskerCacheTick = Long.MIN_VALUE;
    }

    /**
     * How far ahead to look, given how fast the aircraft is going. Pure: the self-check pins that a
     * fast jet looks further than a slow one and that both stay inside the band.
     */
    public static double probeDistance(double speed) {
        return Mth.clamp(Math.max(speed, 0.0) * PROBE_LOOKAHEAD_TICKS, PROBE_MIN, PROBE_MAX);
    }

    /** Highest surface along a straight leg, sampled every {@link #SAMPLE_STEP} blocks. */
    public static int ridgeAlong(Level level, double ox, double oz, Vec3 dir, double length) {
        int highest = Integer.MIN_VALUE;
        for (double d = SAMPLE_STEP; d <= length; d += SAMPLE_STEP) {
            int h = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                    Mth.floor(ox + dir.x * d), Mth.floor(oz + dir.z * d));
            if (h > highest) highest = h;
        }
        return highest == Integer.MIN_VALUE
                ? level.getHeight(Heightmap.Types.WORLD_SURFACE, Mth.floor(ox), Mth.floor(oz))
                : highest;
    }

    /**
     * Best heading to actually fly, given where we want to go and how high we are willing to be.
     * Returns the desired bearing when it is already clear, a deflection when it is not, and
     * {@code null} when every bearing in the fan is blocked — which is the caller's cue to climb,
     * not to keep steering.
     *
     * <p>Two-deep: among the legs that are clear, one whose continuation is also clear is preferred
     * over a nearer deflection that leads into a wall. That single extra level is the difference
     * between routing round a ridge and flying into the valley behind it.
     */
    public Vec3 corridorBearing(Level level, double ox, double oz, Vec3 desired, double clearY,
                                double lookahead, long gameTime) {
        Vec3 desiredN = desired.lengthSqr() > 1.0E-8 ? desired.normalize() : new Vec3(0, 0, 1);
        int headingQ = Mth.floor(Math.toDegrees(Math.atan2(desiredN.x, desiredN.z)) / 5.0);
        int clearYFloor = Mth.floor(clearY);
        if (this.cacheValid && gameTime - this.cacheTick < CACHE_TTL_TICKS
                && headingQ == this.cacheHeadingQ && clearYFloor == this.cacheClearY) {
            return this.cacheDir;
        }

        Vec3 found = search(level, ox, oz, desiredN, clearY, lookahead);
        this.cacheValid = true;
        this.cacheTick = gameTime;
        this.cacheHeadingQ = headingQ;
        this.cacheClearY = clearYFloor;
        this.cacheDir = found;
        return found;
    }

    private static Vec3 search(Level level, double ox, double oz, Vec3 desiredN, double clearY,
                               double lookahead) {
        double threshold = clearY - CLEAR_MARGIN;
        Vec3[] clearDirs = new Vec3[FAN_DEG.length];
        double[] clearCost = new double[FAN_DEG.length];
        int clearCount = 0;

        for (double offDeg : FAN_DEG) {
            Vec3 dir = PlaneNav.rotateY(desiredN, Math.toRadians(offDeg));
            if (ridgeAlong(level, ox, oz, dir, lookahead) > threshold) continue;
            clearDirs[clearCount] = dir;
            clearCost[clearCount] = Math.abs(offDeg);
            clearCount++;
        }
        if (clearCount == 0) return null;

        // Depth two, on the cheapest few only: does the leg actually lead anywhere?
        int expand = Math.min(EXPAND_BEST, clearCount);
        double bestCost = Double.MAX_VALUE;
        Vec3 best = null;
        for (int i = 0; i < clearCount; i++) {
            double cost = clearCost[i];
            if (i < expand) {
                Vec3 dir = clearDirs[i];
                double nx = ox + dir.x * lookahead;
                double nz = oz + dir.z * lookahead;
                if (ridgeAlong(level, nx, nz, dir, lookahead) > threshold) cost += DEAD_END_COST;
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = clearDirs[i];
            }
        }
        return best;
    }

    /**
     * Altitude the planned dive is at, {@code d} blocks along a run of {@code runLength} that
     * descends from {@code startY} to {@code endY}. Straight line, deliberately: the aircraft is
     * being asked whether a dive it has not flown yet is survivable, so the profile has to be the
     * intended one, not the one it happens to be in.
     */
    public static double diveAltitudeAt(double startY, double endY, double runLength, double d) {
        if (runLength <= 1.0E-4) return endY;
        double f = Mth.clamp(d / runLength, 0.0, 1.0);
        return startY + (endY - startY) * f;
    }

    /**
     * Would the planned dive clear the ground all the way in? Interpolates from the aircraft's
     * current altitude down to {@code endY} across the run and requires {@code minClearance} of air
     * under every sample.
     *
     * <p><b>{@code endY} is the altitude the aircraft levels at, not the target's own.</b> An
     * earlier version took a descent gradient aimed at the target itself, which makes the last
     * sample of every run against a ground target sit on the deck by construction — the check then
     * answered "unsafe" for every ground attack that has ever been proposed, the aircraft never
     * left {@code INGRESS}, and it flew over its target at cruise height without firing. A dive
     * ends at a firing altitude and is followed by a pull-up; it does not end at the target.
     */
    public static boolean diveSafe(Level level, double ox, double oy, double oz, Vec3 dir,
                                   double runLength, double endY, double minClearance) {
        double step = runLength / DIVE_SAMPLES;
        for (int i = 1; i <= DIVE_SAMPLES; i++) {
            double d = step * i;
            double y = diveAltitudeAt(oy, endY, runLength, d);
            int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                    Mth.floor(ox + dir.x * d), Mth.floor(oz + dir.z * d));
            if (y - surface < minClearance) return false;
        }
        return true;
    }

    /**
     * Whisker convenience: is this bearing clear for the aircraft's current speed? Kept here so
     * every caller uses the same speed-scaled reach rather than each picking its own constant.
     *
     * <p>Cached a few ticks by quantized heading and speed so an orbiting hold does not re-fan
     * the block whisker every tick — the corridor already does the same for heightmap routing.
     */
    public Vec3 clearBearing(AirTerrainSensor sensor, Vec3 desired, double speed) {
        Vec3 desiredN = desired.lengthSqr() > 1.0E-8 ? desired.normalize() : desired;
        int headingQ = desiredN.lengthSqr() > 1.0E-8
                ? Mth.floor(Math.toDegrees(Math.atan2(desiredN.x, desiredN.z)) / WHISKER_HEADING_BUCKET_DEG)
                : 0;
        int speedQ = Mth.floor(Math.max(speed, 0.0) / WHISKER_SPEED_BUCKET);
        long gameTime = sensor.gameTime();
        if (this.whiskerCacheValid && gameTime - this.whiskerCacheTick < WHISKER_CACHE_TTL_TICKS
                && headingQ == this.whiskerHeadingQ && speedQ == this.whiskerSpeedQ) {
            return this.whiskerCacheDir;
        }

        Vec3 found = sensor.chooseClearBearing(desired, probeDistance(speed));
        this.whiskerCacheValid = true;
        this.whiskerCacheTick = gameTime;
        this.whiskerHeadingQ = headingQ;
        this.whiskerSpeedQ = speedQ;
        this.whiskerCacheDir = found;
        return found;
    }

    /** Highest surface on the leg to a point, including a lateral band — the cruise hold floor. */
    public static int ridgeToward(VehicleEntity vehicle, double toX, double toZ, double lookahead) {
        Level level = vehicle.level();
        double dx = toX - vehicle.getX();
        double dz = toZ - vehicle.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0E-4) {
            return level.getHeight(Heightmap.Types.WORLD_SURFACE,
                    vehicle.getBlockX(), vehicle.getBlockZ());
        }
        Vec3 dir = new Vec3(dx / dist, 0.0, dz / dist);
        return ridgeAlong(level, vehicle.getX(), vehicle.getZ(), dir, Math.min(dist, lookahead));
    }
}
