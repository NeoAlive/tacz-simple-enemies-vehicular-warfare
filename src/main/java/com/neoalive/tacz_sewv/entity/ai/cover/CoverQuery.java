package com.neoalive.tacz_sewv.entity.ai.cover;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.entity.ai.sensor.CombatantIndex;
import com.neoalive.tacz_sewv.entity.ai.sensor.ContactSight;

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
        int dTerrain = CoverVisibilityCache.distance(level, Mth.floor(x), Mth.floor(z), dir);
        double dOcc = Math.min(dTerrain, wreckDistance(level, x, z, threatX, threatZ));
        if (dOcc >= CoverVisibilityCache.MAX_RANGE) return 1.0;
        // Occluder closer than threat ⇒ covered; ratio is remaining "open" fraction to the threat.
        return Mth.clamp(dOcc / D, 0.0, 1.0);
    }

    /**
     * {@link #exposure} without the wreck overlay: one table read. For callers that ask many times per
     * path node (secondary threats), where the overlay's linear walk over every wreck would multiply.
     */
    public static double terrainExposure(ServerLevel level, double x, double z, double threatX, double threatZ) {
        double dx = threatX - x;
        double dz = threatZ - z;
        double D = Math.sqrt(dx * dx + dz * dz);
        if (D < 1.0E-3) return 0.0;
        int dTerrain = CoverVisibilityCache.distance(level, Mth.floor(x), Mth.floor(z),
                CoverVisibilityCache.compass8(dx, dz));
        if (dTerrain >= CoverVisibilityCache.MAX_RANGE) return 1.0;
        return Mth.clamp(dTerrain / D, 0.0, 1.0);
    }

    /**
     * Distance to the first wreck on the line to the threat, or {@code MAX_RANGE}. Wrecks are entities, so the
     * block-baked table never sees them; this is an overlay read from {@link CombatantIndex}'s last rebuild
     * (boxes up to {@code combatantIndexIntervalTicks} stale — wrecks barely move).
     */
    static double wreckDistance(ServerLevel level, double x, double z, double threatX, double threatZ) {
        CombatantIndex.Snapshot snap = CombatantIndex.snapshot(level);
        if (snap == null || !CoverVisibilityCache.enabled()) return CoverVisibilityCache.MAX_RANGE;
        return wreckDistance(snap.wrecks(), x, z, threatX, threatZ);
    }

    /**
     * 2D (x,z) slab test of the segment point→threat against each box; the entry distance of the nearest hit,
     * capped at {@code MAX_RANGE}. A point inside a box answers 0 (fully masked).
     */
    // ponytail: linear over every wreck in the level and ignores height difference (a wreck on a cliff above still
    // counts); bucket by chunk / add a Y band if wreck counts or terrain make that matter.
    static double wreckDistance(List<AABB> wrecks, double x, double z, double threatX, double threatZ) {
        double dx = threatX - x;
        double dz = threatZ - z;
        double len = Math.sqrt(dx * dx + dz * dz);
        double best = CoverVisibilityCache.MAX_RANGE;
        for (AABB b : wrecks) {
            double[] t = {0.0, 1.0};
            if (!slab(x, dx, b.minX, b.maxX, t) || !slab(z, dz, b.minZ, b.maxZ, t)) continue;
            best = Math.min(best, t[0] * len);
        }
        return best;
    }

    /** Narrow {@code t = [enter, exit]} to where {@code p + d*t} is inside {@code [min, max]}; false if empty. */
    private static boolean slab(double p, double d, double min, double max, double[] t) {
        if (Math.abs(d) < 1.0E-9) return p >= min && p <= max;
        double a = (min - p) / d;
        double c = (max - p) / d;
        t[0] = Math.max(t[0], Math.min(a, c));
        t[1] = Math.min(t[1], Math.max(a, c));
        return t[0] <= t[1];
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
        double dThreat = Math.min(CoverVisibilityCache.distance(level, Mth.floor(x), Mth.floor(z), threatDir),
                wreckDistance(level, x, z, threatX, threatZ));
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
     * Farther break-contact point: prefer positions away from the threat with low exposure
     * (masked by an occluder closer than the threat). Used by SEEK_COVER under retreat/smoke.
     */
    @Nullable
    public static Vec3 suggestRetreatCover(ServerLevel level, VehicleEntity hull,
                                           double threatX, double threatZ) {
        double hx = hull.getX();
        double hz = hull.getZ();
        double toThreatX = threatX - hx;
        double toThreatZ = threatZ - hz;
        double len = Math.sqrt(toThreatX * toThreatX + toThreatZ * toThreatZ);
        if (len < 1.0E-3) return null;
        double fx = toThreatX / len;
        double fz = toThreatZ / len;
        double lx = -fz;
        double lz = fx;
        int parity = Integer.signum(hull.getId()) >= 0 ? 1 : -1;

        double bestScore = Double.NEGATIVE_INFINITY;
        Vec3 best = null;
        // Rings behind / lateral-behind the hull; farther than fire-and-maneuver scoot.
        for (double back : new double[] {12.0, 18.0, 24.0, 30.0}) {
            for (int side : new int[] {0, parity, -parity}) {
                double lat = side == 0 ? 0.0 : 8.0 + Math.abs(side) * 4.0;
                double px = hx - fx * back + lx * side * lat;
                double pz = hz - fz * back + lz * side * lat;
                double exp = exposure(level, px, pz, threatX, threatZ);
                if (exp >= 0.55) continue;
                double distMove = Math.sqrt((px - hx) * (px - hx) + (pz - hz) * (pz - hz));
                // Prefer masked + a clean break distance (~18–24).
                double score = (1.0 - exp) * 12.0 - Math.abs(distMove - 20.0) * 0.15;
                if (score > bestScore) {
                    bestScore = score;
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

    /** A picked battle position: where to fight from, where to go next, and whether the hull sits hull-down. */
    public record BattlePick(Vec3 primary, @Nullable Vec3 alternate, boolean hullDown) {}

    private static final double BP_ARC_RAD = Math.toRadians(60.0);
    private static final int BP_BEARINGS = 8;
    /** Candidates that get raycast, best cheap score first. Two rays each at most. */
    private static final int BP_RAYCAST = 12;
    private static final double BP_ALTERNATE_SPACING = 10.0;
    private static final double BP_MAX_RISE = 16.0;

    /**
     * Battle position candidates on the engagement band around {@code target}, inside 60 degrees either side of
     * the hull's current bearing so the crew never crosses the field to reach one. {@code threats} is the packed
     * x,z array {@code TacticalPosture} already publishes for the path malus (primary first, then anti-armour
     * contacts); a candidate open to any of them ranks lower.
     *
     * <p>The cache bakes one height, so it cannot tell hull-down from fully masked: it only ranks. The verdict is
     * two {@link ContactSight} rays per candidate from its surface — the turret height must see the target, and
     * the hull's own height being blocked is the hull-down bonus. Rays never load a chunk; a candidate whose
     * chunk is not loaded is skipped.
     *
     * @return null when nothing on the band is hull-down or at least partly covered and able to shoot
     */
    @Nullable
    public static BattlePick suggestBattlePositions(ServerLevel level, VehicleEntity hull, LivingEntity target,
                                                    long[] threats, double ring, double band) {
        double tx = target.getX();
        double tz = target.getZ();
        double base = Math.atan2(hull.getZ() - tz, hull.getX() - tx);
        double[] radii = {Math.max(4.0, ring - band * 0.5), ring, ring + band * 0.5};
        double turretRise = Math.max(1.5, hull.getBbHeight() - 0.3);
        Vec3 aim = target.getEyePosition();

        List<double[]> cands = new ArrayList<>(BP_BEARINGS * radii.length);
        for (int b = 0; b < BP_BEARINGS; b++) {
            double ang = base - BP_ARC_RAD + 2.0 * BP_ARC_RAD * b / (BP_BEARINGS - 1);
            double cx = Math.cos(ang);
            double cz = Math.sin(ang);
            for (double r : radii) {
                double px = tx + cx * r;
                double pz = tz + cz * r;
                double ground = surfaceY(level, px, pz);
                if (Double.isNaN(ground) || Math.abs(ground - hull.getY()) > BP_MAX_RISE) continue;
                double expPrimary = exposure(level, px, pz, Double.longBitsToDouble(threats[0]),
                        Double.longBitsToDouble(threats[1]));
                double worstExtra = 0.0;
                for (int i = 2; i + 1 < threats.length; i += 2) {
                    worstExtra = Math.max(worstExtra, exposure(level, px, pz,
                            Double.longBitsToDouble(threats[i]), Double.longBitsToDouble(threats[i + 1])));
                }
                double travel = Math.sqrt((px - hull.getX()) * (px - hull.getX()) + (pz - hull.getZ()) * (pz - hull.getZ()));
                double cheap = (1.0 - worstExtra) * 3.0 + (1.0 - expPrimary)
                        + keyholeQuality(level, px, pz, tx, tz) - travel * 0.05;
                cands.add(new double[] {px, pz, ground, cheap, expPrimary, b});
            }
        }
        // Ties fall to the bearing index, so mirrored terrain gives a stable answer.
        cands.sort(Comparator.<double[]>comparingDouble(c -> -c[3]).thenComparingDouble(c -> c[5]));

        List<double[]> ok = new ArrayList<>();
        for (int i = 0; i < cands.size() && i < BP_RAYCAST; i++) {
            double[] c = cands.get(i);
            Vec3 turret = new Vec3(c[0], c[2] + turretRise, c[1]);
            if (ContactSight.between(level, turret, aim, null) != ContactSight.Kind.CLEAR) continue;
            Vec3 body = new Vec3(c[0], c[2] + 1.0, c[1]);
            boolean hullDown = ContactSight.between(level, body, aim, null) == ContactSight.Kind.BLOCKED;
            if (!hullDown && c[4] >= 0.9) continue; // open ground: nothing to hold
            ok.add(new double[] {c[0], c[1], c[2], c[3] + (hullDown ? 6.0 : 0.0), hullDown ? 1 : 0});
        }
        if (ok.isEmpty()) return null;
        ok.sort(Comparator.comparingDouble(c -> -c[3]));
        double[] best = ok.get(0);
        Vec3 alternate = null;
        for (int i = 1; i < ok.size(); i++) {
            double[] c = ok.get(i);
            double dx = c[0] - best[0];
            double dz = c[1] - best[1];
            if (dx * dx + dz * dz >= BP_ALTERNATE_SPACING * BP_ALTERNATE_SPACING) {
                alternate = new Vec3(c[0], c[2], c[1]);
                break;
            }
        }
        return new BattlePick(new Vec3(best[0], best[2], best[1]), alternate, best[4] > 0);
    }

    /** Surface Y at a column, NaN when its chunk is not loaded or the surface is a fluid. Never loads a chunk. */
    private static double surfaceY(ServerLevel level, double x, double z) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        LevelChunk chunk = level.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
        if (chunk == null) return Double.NaN;
        int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx & 15, bz & 15) + 1;
        if (!chunk.getFluidState(new BlockPos(bx, y - 1, bz)).isEmpty()) return Double.NaN;
        return y;
    }
}
