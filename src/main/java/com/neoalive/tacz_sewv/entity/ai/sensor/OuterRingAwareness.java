package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.init.ModGameRules;

/**
 * Outer awareness ring for ground crews: banded, coarse heightmap spotting beyond the mounted
 * target-scan cylinder, plus foliage-obscured contacts offered from the inner scan.
 *
 * <p><b>Never</b> calls {@code setTarget}. Spots are offered to {@link AwarenessCues}, which
 * publishes {@link com.neoalive.tacz_sewv.entity.ai.utility.Facts.Memory#noteSpot} (when idle)
 * and {@code DISTANT_CONTACT} — engagement stays exclusively with the mounted target-scan goal.
 *
 * <p>Per-hull state owned by {@code DriveVehicleGoal}. Occlusion results are not cached across
 * polls (the hull moves). Candidate lists are not cached either — re-query on each band poll.
 */
public final class OuterRingAwareness {

    private static final Logger LOG = LogUtils.getLogger();

    private static final int BANDS = 4;
    /** Two chunks of radial width per band (except the edge band, which runs to outer max). */
    private static final double BAND_WIDTH = 32.0;
    /** Near / mid / far / edge poll intervals (game ticks). Not config — tested defaults. */
    private static final int[] BAND_INTERVAL_TICKS = {40, 80, 120, 200};
    private static final int OCCLUSION_SAMPLES = 2;
    private static final int MAX_CANDIDATES = 8;

    /** Strength offered to {@link AwarenessCues} by band (near → edge). */
    private static final double[] BAND_STRENGTH = {1.0, 0.75, 0.5, 0.25};

    /**
     * Foliage-only contacts from the mounted cylinder scan: same DISTANT_CONTACT channel,
     * near-band strength. Keyed by hull id; consumed on the next {@link #tick}.
     */
    private static final ConcurrentHashMap<Integer, FoliageOffer> FOLIAGE_OFFERS = new ConcurrentHashMap<>();

    private VehicleEntity hull;
    private final long[] bandDeadline = new long[BANDS];

    public void clear() {
        if (this.hull != null) FOLIAGE_OFFERS.remove(this.hull.getId());
        this.hull = null;
        Arrays.fill(this.bandDeadline, Long.MIN_VALUE);
    }

    /**
     * Inner-cylinder contact visible only through leaves — not engageable, but something is
     * there. Reuses the {@link AwarenessCues} investigate path.
     */
    public static void offerFoliageContact(VehicleEntity hull, LivingEntity contact) {
        if (hull == null || contact == null || !contact.isAlive()) return;
        double dist = Math.sqrt(horizontalDistSq(hull, contact));
        FOLIAGE_OFFERS.put(hull.getId(), new FoliageOffer(contact.getId(), contact.blockPosition(), dist));
    }

    /**
     * Poll outer bands and offer entity spots to {@link AwarenessCues}. Call before
     * {@link AwarenessCues#tick}.
     */
    public void tick(AbstractUnit unit, VehicleEntity vehicle, AwarenessCues cues, boolean underOrders) {
        if (underOrders || unit.getTarget() != null) {
            FOLIAGE_OFFERS.remove(vehicle.getId());
            return;
        }

        if (this.hull != vehicle) {
            clear();
            this.hull = vehicle;
            long now = unit.level().getGameTime();
            for (int b = 0; b < BANDS; b++) {
                this.bandDeadline[b] = now + Math.floorMod(vehicle.getId() + b * 7, interval(b));
            }
        }

        long now = unit.level().getGameTime();
        consumeFoliageOffer(unit, vehicle, cues, now);

        if (!SewvConfig.SPEC.isLoaded() || !SewvConfig.OUTER_RING_ENABLED.get()) {
            return;
        }

        double inner = SewvConfig.VEHICLE_TARGET_SCAN_RADIUS.get();
        double outer = outerRadius(vehicle);
        if (outer > inner) {
            int band = pickOverdueBand(now);
            if (band >= 0) {
                poll(unit, vehicle, band, inner, outer, now, cues);
                this.bandDeadline[band] = nextDeadline(now, interval(band));
            }
        }
    }

    private void consumeFoliageOffer(AbstractUnit unit, VehicleEntity vehicle, AwarenessCues cues,
            long now) {
        FoliageOffer offer = FOLIAGE_OFFERS.remove(vehicle.getId());
        if (offer == null || unit.getTarget() != null) return;

        Entity e = unit.level().getEntity(offer.id);
        if (!(e instanceof LivingEntity living) || !living.isAlive()) return;

        cues.offerEntitySpot(offer.id, offer.pos, offer.dist, BAND_STRENGTH[0], now, living);
        debug("foliage hull=#{} cand=#{} dist={}", vehicle.getId(), offer.id, offer.dist);
    }

    public static long nextDeadline(long now, int interval) {
        return now + Math.max(1, interval);
    }

    public static int simulateMaxPollsPerTick(int hullCount, int interval, int ticks) {
        int i = Math.max(1, interval);
        long[] deadline = new long[hullCount];
        for (int id = 0; id < hullCount; id++) {
            deadline[id] = Math.floorMod(id, i);
        }
        int max = 0;
        for (long t = 0; t < ticks; t++) {
            int polls = 0;
            for (int id = 0; id < hullCount; id++) {
                if (t >= deadline[id]) {
                    polls++;
                    deadline[id] = nextDeadline(t, i);
                }
            }
            max = Math.max(max, polls);
        }
        return max;
    }

    public static int simulateMaxPollsUnstaggered(int hullCount, int interval, int ticks) {
        long[] deadline = new long[hullCount];
        int max = 0;
        for (long t = 0; t < ticks; t++) {
            int polls = 0;
            for (int id = 0; id < hullCount; id++) {
                if (t >= deadline[id]) {
                    polls++;
                    deadline[id] = t + Math.max(1, interval);
                }
            }
            max = Math.max(max, polls);
        }
        return max;
    }

    private void poll(AbstractUnit unit, VehicleEntity vehicle, int band,
                      double inner, double outer, long now, AwarenessCues cues) {
        double lo = bandLo(inner, band);
        double hi = bandHi(inner, outer, band);
        if (hi <= lo) return;

        double halfH = SewvConfig.VEHICLE_TARGET_SCAN_HEIGHT.get() / 2.0;
        AABB box = new AABB(
                vehicle.getX() - hi, vehicle.getY() - halfH, vehicle.getZ() - hi,
                vehicle.getX() + hi, vehicle.getY() + halfH, vehicle.getZ() + hi);

        double loSq = lo * lo;
        double hiSq = hi * hi;
        double globalInnerSq = inner * inner;
        int cap = MAX_CANDIDATES;

        List<LivingEntity> candidates = new ArrayList<>();
        for (LivingEntity e : unit.level().getEntitiesOfClass(LivingEntity.class, box, ent ->
                isCandidate(unit, vehicle, ent))) {
            double d = horizontalDistSq(vehicle, e);
            if (d <= globalInnerSq) continue;
            if (d <= loSq || d > hiSq) continue;
            candidates.add(e);
        }

        candidates.sort(Comparator.comparingDouble(e -> horizontalDistSq(vehicle, e)));
        if (candidates.size() > cap) {
            candidates = candidates.subList(0, cap);
        }

        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity e : candidates) {
            if (unit.getTarget() == e) continue;
            Vec3 from = new Vec3(vehicle.getX(), vehicle.getEyeY(), vehicle.getZ());
            Vec3 to = new Vec3(e.getX(), e.getEyeY(), e.getZ());
            if (!coarseVisible(unit.level(), from, to, OCCLUSION_SAMPLES)) {
                debug("occluded hull=#{} band={} cand=#{}", vehicle.getId(), band, e.getId());
                continue;
            }
            double d = Math.sqrt(horizontalDistSq(vehicle, e));
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }

        debug("poll hull=#{} band={} candidates={} spotted={}",
                vehicle.getId(), band, candidates.size(),
                best == null ? -1 : best.getId());

        if (best != null) {
            cues.offerEntitySpot(best.getId(), best.blockPosition(), bestDist, BAND_STRENGTH[band],
                    now, best);
        }
    }

    public static boolean coarseVisible(Level level, Vec3 from, Vec3 to, int samples) {
        int n = Mth.clamp(samples, 1, 10);
        for (int i = 1; i <= n; i++) {
            double t = i / (double) (n + 1);
            int x = Mth.floor(from.x + (to.x - from.x) * t);
            int z = Mth.floor(from.z + (to.z - from.z) * t);
            if (!level.hasChunk(x >> 4, z >> 4)) return false;
            int terrainY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            double lineY = from.y + (to.y - from.y) * t;
            if (terrainY > lineY) return false;
        }
        return true;
    }

    private static boolean isCandidate(AbstractUnit unit, VehicleEntity vehicle, LivingEntity e) {
        if (e == unit || !e.isAlive() || !e.isAttackable()) return false;
        if (e.getVehicle() == vehicle) return false;
        if (!(e instanceof AbstractUnit || e instanceof Player)) return false;
        if (e instanceof Player p && (p.isCreative() || p.isSpectator())) return false;
        return !VehicleTargeting.isNonHostile(unit, e);
    }

    private int pickOverdueBand(long now) {
        for (int b = 0; b < BANDS; b++) {
            if (now >= this.bandDeadline[b]) return b;
        }
        return -1;
    }

    private static int interval(int band) {
        int i = Math.max(0, Math.min(band, BAND_INTERVAL_TICKS.length - 1));
        return BAND_INTERVAL_TICKS[i];
    }

    public static double bandLo(double inner, int band) {
        return inner + band * BAND_WIDTH;
    }

    public static double bandHi(double inner, double outer, int band) {
        if (band >= BANDS - 1) return outer;
        return inner + (band + 1) * BAND_WIDTH;
    }

    private static double outerRadius(VehicleEntity vehicle) {
        double configured = SewvConfig.OUTER_RING_MAX_BLOCKS.get();
        int simChunks = 10;
        if (vehicle.level() instanceof ServerLevel sl) {
            simChunks = sl.getServer().getPlayerList().getSimulationDistance();
        }
        return Math.min(configured, simChunks * 16.0);
    }

    private static double horizontalDistSq(VehicleEntity v, LivingEntity e) {
        double dx = e.getX() - v.getX();
        double dz = e.getZ() - v.getZ();
        return dx * dx + dz * dz;
    }

    private static void debug(String msg, Object... args) {
        if (!ModGameRules.server(ModGameRules.OUTER_RING_DEBUG_LOGGING)) return;
        LOG.info("[sewv-outer] " + msg, args);
    }

    private record FoliageOffer(int id, BlockPos pos, double dist) {}
}
