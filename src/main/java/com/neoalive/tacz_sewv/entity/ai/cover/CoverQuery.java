package com.neoalive.tacz_sewv.entity.ai.cover;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * O(1) cover queries over {@link CoverVisibilityCache}. No raycasts — validation is the caller's
 * optional rare {@code ContactSight} check when committing a scoot/keyhole.
 */
public final class CoverQuery {

    private static final double SCOOT_MIN = 8.0;
    private static final double SCOOT_MAX = 16.0;
    private static final double LATERAL_STEP = 4.0;

    private CoverQuery() {}

    /** 0 = fully masked by an occluder closer than the threat; 1 = fully exposed. */
    public static double exposure(ServerLevel level, double x, double z,
                                  double threatX, double threatZ) {
        double dx = threatX - x;
        double dz = threatZ - z;
        double D = Math.sqrt(dx * dx + dz * dz);
        if (D < 1.0E-3) return 0.0;
        int dir = CoverVisibilityCache.compass8(dx, dz);
        int dOcc = CoverVisibilityCache.distance(level, Mth.floor(x), Mth.floor(z), dir);
        if (dOcc >= CoverVisibilityCache.MAX_RANGE) return 1.0;
        // Occluder closer than threat ⇒ covered; ratio is remaining "open" fraction to the threat.
        return Mth.clamp(dOcc / D, 0.0, 1.0);
    }

    public static boolean isCovered(ServerLevel level, double x, double z,
                                    double threatX, double threatZ) {
        return exposure(level, x, z, threatX, threatZ) < 0.55;
    }

    /**
     * Keyhole quality 0..1: covered toward the threat, open on an adjacent compass dir
     * (corner / wall peek).
     */
    public static double keyholeQuality(ServerLevel level, double x, double z,
                                        double threatX, double threatZ) {
        double dx = threatX - x;
        double dz = threatZ - z;
        double D = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
        int threatDir = CoverVisibilityCache.compass8(dx, dz);
        int dThreat = CoverVisibilityCache.distance(level, Mth.floor(x), Mth.floor(z), threatDir);
        if (dThreat >= D || dThreat >= CoverVisibilityCache.MAX_RANGE) return 0.0;
        double cover = 1.0 - Mth.clamp(dThreat / D, 0.0, 1.0);
        double bestOpen = 0.0;
        for (int side : new int[] {-1, 1}) {
            int adj = (threatDir + side) & 7;
            int dAdj = CoverVisibilityCache.distance(level, Mth.floor(x), Mth.floor(z), adj);
            bestOpen = Math.max(bestOpen, Mth.clamp(dAdj / (double) CoverVisibilityCache.MAX_RANGE, 0.0, 1.0));
        }
        return Mth.clamp(cover * bestOpen, 0.0, 1.0);
    }

    /**
     * Suggest a nearby displace point. {@code breakLos} prefers lower exposure (mask);
     * otherwise prefers a lateral move that keeps exposure high enough to still see the threat.
     */
    @Nullable
    public static Vec3 suggestDisplace(ServerLevel level, VehicleEntity hull,
                                       LivingEntity threat, boolean breakLos) {
        double hx = hull.getX();
        double hz = hull.getZ();
        double tx = threat.getX();
        double tz = threat.getZ();
        double toThreatX = tx - hx;
        double toThreatZ = tz - hz;
        double len = Math.sqrt(toThreatX * toThreatX + toThreatZ * toThreatZ);
        if (len < 1.0E-3) return null;
        double fx = toThreatX / len;
        double fz = toThreatZ / len;
        // Perpendiculars for lateral scoot.
        double lx = -fz;
        double lz = fx;

        double bestScore = Double.NEGATIVE_INFINITY;
        Vec3 best = null;
        double ring = Mth.clamp(len, SCOOT_MIN, SCOOT_MAX + 8.0);
        int parity = Integer.signum(hull.getId()) >= 0 ? 1 : -1;

        for (int side : new int[] {parity, -parity}) {
            for (double lat = LATERAL_STEP; lat <= SCOOT_MAX; lat += LATERAL_STEP) {
                double px = hx + lx * side * lat;
                double pz = hz + lz * side * lat;
                // Nudge slightly toward/away to stay near the engagement ring.
                double along = (len - ring) * 0.35;
                px -= fx * along;
                pz -= fz * along;
                double exp = exposure(level, px, pz, tx, tz);
                double distMove = Math.sqrt((px - hx) * (px - hx) + (pz - hz) * (pz - hz));
                if (distMove < SCOOT_MIN * 0.5 || distMove > SCOOT_MAX + 4.0) continue;
                double score;
                if (breakLos) {
                    // Prefer masked positions, mild preference for moderate displace distance.
                    score = (1.0 - exp) * 10.0 - Math.abs(distMove - 12.0) * 0.1;
                } else {
                    // Keep LOS: exposure must stay relatively open, still displace.
                    if (exp < 0.45) continue;
                    score = exp * 4.0 + Math.min(distMove, 12.0) * 0.2;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = new Vec3(px, hull.getY(), pz);
                }
            }
        }
        return best;
    }

    /**
     * Micro-offset toward a neighbouring 2×2 cell that improves keyhole quality.
     */
    @Nullable
    public static Vec3 suggestKeyhole(ServerLevel level, VehicleEntity hull,
                                      LivingEntity threat) {
        double hx = hull.getX();
        double hz = hull.getZ();
        double tx = threat.getX();
        double tz = threat.getZ();
        double bestQ = keyholeQuality(level, hx, hz, tx, tz);
        Vec3 best = null;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                double px = hx + dx * CoverVisibilityCache.CELL;
                double pz = hz + dz * CoverVisibilityCache.CELL;
                double q = keyholeQuality(level, px, pz, tx, tz);
                if (q > bestQ + 0.05) {
                    bestQ = q;
                    best = new Vec3(px, hull.getY(), pz);
                }
            }
        }
        return best;
    }

    /**
     * Fan-slot cover interest: for each of 7 heading offsets, how much a one-hull-length step
     * in that direction reduces exposure to the threat (0..1).
     */
    public static void fillCoverInterest(ServerLevel level, double x, double z, float yawDeg,
                                         double threatX, double threatZ, float[] out7,
                                         double[] slotsDeg) {
        double cur = exposure(level, x, z, threatX, threatZ);
        double step = 4.0;
        for (int i = 0; i < out7.length && i < slotsDeg.length; i++) {
            double bearing = Math.toRadians(yawDeg + slotsDeg[i]);
            // MC: yaw 0 looks south (+Z?); VehicleDriver uses standard look — match atan2 style
            // used elsewhere: forward = (-sin yaw, cos yaw) in Mojmap degrees.
            double fx = -Math.sin(bearing);
            double fz = Math.cos(bearing);
            double nx = x + fx * step;
            double nz = z + fz * step;
            double next = exposure(level, nx, nz, threatX, threatZ);
            out7[i] = (float) Mth.clamp(cur - next, 0.0, 1.0);
        }
    }
}
