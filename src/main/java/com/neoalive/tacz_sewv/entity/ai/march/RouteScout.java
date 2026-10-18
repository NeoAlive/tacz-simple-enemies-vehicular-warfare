package com.neoalive.tacz_sewv.entity.ai.march;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Lays a march's road ahead of it, a leg at a time, from heightmap reads alone.
 *
 * <p>A march holds a <b>bearing</b> — the direction it set off in — and each leg tries that bearing first, then
 * swings either side of it in widening steps until one runs over drivable ground: no water, and no step of more
 * than {@link #MAX_STEP_RISE} blocks between samples (the same "a hill climbs, a wall jumps" rule the idle
 * bearing pick always used). Measuring the swing from the bearing rather than from the last leg is what stops
 * the road wandering off in a spiral round an obstacle; capping the turn between legs is what keeps it a road.
 *
 * <p>Nothing here loads a chunk. A leg reaching into an unloaded chunk is simply not laid this pass — the march
 * reaches the end of what is laid and waits there, or ends if it never loads.
 */
public final class RouteScout {

    static final double LEG = 24.0;
    private static final int STEPS = 3;
    static final int MAX_STEP_RISE = 2;
    /** Swing from the march bearing, tried in this order (degrees). */
    private static final double[] SWINGS = {0.0, 20.0, -20.0, 40.0, -40.0, 60.0, -60.0};
    /** Largest turn between consecutive legs. */
    private static final double MAX_TURN_RAD = Math.toRadians(65.0);

    public enum Result {
        /** The road is as long as asked for. */
        READY,
        /** The next leg runs into an unloaded chunk; try again later. */
        WAIT,
        /** No leg is drivable, or the road is as long as it may get: it ends where it is. */
        CLOSED
    }

    private RouteScout() {}

    /**
     * Lay legs until the route is {@code want} long. {@code bearing} is {@code atan2(dx, dz)}; the route never
     * grows past {@code max}.
     */
    public static Result extend(Level level, MarchRoute route, double bearing, double want, double max) {
        while (route.length() < want) {
            if (route.length() + LEG > max) return Result.CLOSED;
            double[] tangent = route.tangentAt(route.length());
            double heading = Math.atan2(tangent[0], tangent[1]);
            double ex = route.endX();
            double ez = route.endZ();
            if (!loaded(level, ex, ez)) return Result.WAIT;
            int y0 = surfaceY(level, ex, ez);
            boolean laid = false;
            for (double swing : SWINGS) {
                double b = bearing + Math.toRadians(swing);
                if (route.points() > 1 && Math.abs(Mth.wrapDegrees(Math.toDegrees(b - heading))) > Math.toDegrees(MAX_TURN_RAD)) {
                    continue;
                }
                Probe p = probe(level, ex, ez, y0, b);
                if (p == Probe.UNLOADED) return Result.WAIT;
                if (p == Probe.CLEAR) {
                    route.append(ex + Math.sin(b) * LEG, ez + Math.cos(b) * LEG);
                    laid = true;
                    break;
                }
            }
            if (!laid) return Result.CLOSED;
        }
        return Result.READY;
    }

    private enum Probe { CLEAR, BLOCKED, UNLOADED }

    private static Probe probe(Level level, double x, double z, int y0, double bearing) {
        double fx = Math.sin(bearing);
        double fz = Math.cos(bearing);
        double step = LEG / STEPS;
        int lastY = y0;
        for (int i = 1; i <= STEPS; i++) {
            double px = x + fx * step * i;
            double pz = z + fz * step * i;
            if (!loaded(level, px, pz)) return Probe.UNLOADED;
            int y = surfaceY(level, px, pz);
            if (Math.abs(y - lastY) > MAX_STEP_RISE) return Probe.BLOCKED;
            if (level.getFluidState(BlockPos.containing(px, y - 1, pz)).is(FluidTags.WATER)) return Probe.BLOCKED;
            lastY = y;
        }
        return Probe.CLEAR;
    }

    /**
     * The bearing to set off on from {@code (x, z)}: of eight evenly spaced headings, the one with the longest
     * run of drivable ground. The sweep starts at {@code seed} radians, so groups on the same open plain do not
     * all pick north — and a seed derived from the group's leader keeps the choice deterministic.
     */
    public static double openBearing(Level level, double x, double z, double seed) {
        double best = seed;
        int bestScore = -1;
        for (int i = 0; i < 8; i++) {
            double b = seed + i * (Math.PI / 4.0);
            int score = 0;
            if (loaded(level, x, z)) {
                int lastY = surfaceY(level, x, z);
                double fx = Math.sin(b);
                double fz = Math.cos(b);
                for (int step = 1; step <= 4; step++) {
                    double px = x + fx * 16.0 * step;
                    double pz = z + fz * 16.0 * step;
                    if (!loaded(level, px, pz)) break;
                    int y = surfaceY(level, px, pz);
                    if (Math.abs(y - lastY) > MAX_STEP_RISE) break;
                    if (level.getFluidState(BlockPos.containing(px, y - 1, pz)).is(FluidTags.WATER)) break;
                    lastY = y;
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = b;
            }
        }
        return best;
    }

    /** A deterministic angle in {@code [0, 2pi)} from an id. */
    public static double seedAngle(int id) {
        long h = (id * 0x9E3779B97F4A7C15L) >>> 11;
        return (h / (double) (1L << 53)) * Math.PI * 2.0;
    }

    private static boolean loaded(Level level, double x, double z) {
        return level.hasChunk(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
    }

    private static int surfaceY(Level level, double x, double z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
    }
}
