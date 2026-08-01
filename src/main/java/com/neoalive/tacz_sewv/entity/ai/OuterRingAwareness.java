package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.utility.Facts;
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
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Outer awareness ring for ground crews: banded, coarse heightmap spotting beyond the mounted
 * target-scan cylinder.
 *
 * <p><b>Never</b> calls {@code setTarget}. Spots feed {@link Facts.Memory#noteSpot} (when idle) and
 * {@link Facts#outerSpotFresh} / {@link Facts#outerSpotStrength} for {@code DISTANT_CONTACT} —
 * engagement stays exclusively with {@link VehicleTargetScanGoal}.
 *
 * <p>Per-hull state owned by {@link DriveVehicleGoal}. Occlusion results are not cached across
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
    /**
     * How long IdleCrewGoal keeps handing SBW the fixed glance bearing so the turret can slew
     * and settle. Not continuous tracking — the vector is frozen at poll time.
     */
    private static final int GLANCE_HOLD_TICKS = 40;

    /** Strength published into Facts by band (near → edge). */
    private static final double[] BAND_STRENGTH = {1.0, 0.75, 0.5, 0.25};

    private VehicleEntity hull;
    private final long[] bandDeadline = new long[BANDS];

    private int spotId = -1;
    @Nullable
    private BlockPos spotPos;
    private long spotSeen = Long.MIN_VALUE;
    private double spotDist = Double.MAX_VALUE;
    private int spotBand = -1;

    @Nullable
    private Vec3 glanceBearing;
    private long glanceUntil = Long.MIN_VALUE;

    public void clear() {
        this.hull = null;
        Arrays.fill(this.bandDeadline, Long.MIN_VALUE);
        dropSpot();
    }

    /**
     * Tick once per driver tick. Safe to call when the outer ring is disabled (clears Facts fields).
     *
     * <p>Call <b>before</b> {@code brain.update} so the same-tick sample sees fresh outer fields.
     * {@link Facts.Memory#noteSpot} is only used when idle; a live lock's {@code observe} still wins.
     */
    public void tick(AbstractUnit unit, VehicleEntity vehicle, Facts facts) {
        if (!SewvConfig.SPEC.isLoaded() || !SewvConfig.OUTER_RING_ENABLED.get()) {
            clearFacts(facts);
            return;
        }
        if (unit.getTarget() != null || facts.underOrders) {
            clearFacts(facts);
            return;
        }

        if (this.hull != vehicle) {
            clear();
            this.hull = vehicle;
            long now = unit.level().getGameTime();
            for (int b = 0; b < BANDS; b++) {
                // Stagger first eligibility so N hulls do not all poll band 0 on the same tick.
                // After that, each poll advances by a fixed interval (see nextDeadline).
                this.bandDeadline[b] = now + Math.floorMod(vehicle.getId() + b * 7, interval(b));
            }
        }

        long now = unit.level().getGameTime();
        prune(unit, vehicle, now);

        double inner = SewvConfig.VEHICLE_TARGET_SCAN_RADIUS.get();
        double outer = outerRadius(vehicle);
        if (outer > inner) {
            int band = pickOverdueBand(now);
            if (band >= 0) {
                poll(unit, vehicle, band, inner, outer, now);
                this.bandDeadline[band] = nextDeadline(now, interval(band));
            }
        }

        publish(unit, facts, now);
    }

    /**
     * Advance a band's deadline by a fixed interval. Phase spreading is done once at attach
     * ({@code now + hullId % interval}); adding another per-hull term here would lengthen
     * periods unevenly and re-bunch fleets.
     * Package-visible for the stagger self-check.
     */
    static long nextDeadline(long now, int interval) {
        return now + Math.max(1, interval);
    }

    /**
     * Simulate max concurrent band polls across {@code hullCount} hulls over {@code ticks}.
     * Used by {@code OuterRingAwarenessSelfCheck} — not a game path.
     */
    static int simulateMaxPollsPerTick(int hullCount, int interval, int ticks) {
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

    /** Same simulation with no stagger (every hull shares deadline 0) — contrast for the self-check. */
    static int simulateMaxPollsUnstaggered(int hullCount, int interval, int ticks) {
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

    private void publish(AbstractUnit unit, Facts facts, long now) {
        boolean fresh = this.spotId >= 0 && this.spotPos != null
                && Facts.ticksSince(this.spotSeen, now) < Facts.CONTACT_MEMORY_TICKS;
        facts.outerSpotFresh = fresh;
        facts.outerSpotDist = fresh ? this.spotDist : Double.MAX_VALUE;
        facts.outerSpotStrength = fresh && this.spotBand >= 0 ? BAND_STRENGTH[this.spotBand] : 0.0;

        // Memory search aimpoint only when idle — a live lock's observe owns lastEnemyPos.
        if (fresh && unit.getTarget() == null) {
            facts.memory.noteSpot(this.spotPos, now);
        }

        // Cosmetic glance: fixed bearing for a short hold, then clear so IdleCrewGoal resumes.
        if (this.glanceBearing != null && now < this.glanceUntil && unit.getTarget() == null) {
            facts.outerGlanceBearing = this.glanceBearing;
            facts.outerGlanceUntil = this.glanceUntil;
        } else {
            this.glanceBearing = null;
            this.glanceUntil = Long.MIN_VALUE;
            facts.outerGlanceBearing = null;
            facts.outerGlanceUntil = Long.MIN_VALUE;
        }
    }

    private void prune(AbstractUnit unit, VehicleEntity vehicle, long now) {
        if (this.spotId < 0) return;

        Level level = unit.level();
        Entity e = level.getEntity(this.spotId);
        if (!(e instanceof LivingEntity living) || !living.isAlive()) {
            dropSpot();
            return;
        }
        BlockPos pos = living.blockPosition();
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            dropSpot();
            return;
        }
        // Inner ring took the lock — outer list must not compete with engageable truth.
        if (unit.getTarget() == living) {
            dropSpot();
            return;
        }
        if (Facts.ticksSince(this.spotSeen, now) >= Facts.CONTACT_MEMORY_TICKS) {
            dropSpot();
            return;
        }

        this.spotPos = pos;
        this.spotDist = Math.sqrt(horizontalDistSq(vehicle, living));
    }

    private void poll(AbstractUnit unit, VehicleEntity vehicle, int band,
                      double inner, double outer, long now) {
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
            if (d <= globalInnerSq) continue; // inner ring owns these
            if (d <= loSq || d > hiSq) continue;
            candidates.add(e);
        }

        candidates.sort(Comparator.comparingDouble(e -> horizontalDistSq(vehicle, e)));
        if (candidates.size() > cap) {
            candidates = candidates.subList(0, cap);
        }

        int samples = OCCLUSION_SAMPLES;
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        // Within-poll only: no cross-poll occlusion cache (hull moves every tick).
        for (LivingEntity e : candidates) {
            if (unit.getTarget() == e) continue;
            Vec3 from = new Vec3(vehicle.getX(), vehicle.getEyeY(), vehicle.getZ());
            Vec3 to = new Vec3(e.getX(), e.getEyeY(), e.getZ());
            if (!coarseVisible(unit.level(), from, to, samples)) {
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
            this.spotId = best.getId();
            this.spotPos = best.blockPosition();
            this.spotSeen = now;
            this.spotDist = bestDist;
            this.spotBand = band;
            armGlance(unit, vehicle, best, now);
        }
    }

    /**
     * One cosmetic turret glance per successful poll: freeze the look vector at poll time.
     * Skipped while engaged so SBW's UUID aim is never contested. IdleCrewGoal holds the
     * bearing for {@link #GLANCE_HOLD_TICKS} then stops — not continuous tracking.
     */
    private void armGlance(AbstractUnit unit, VehicleEntity vehicle, LivingEntity spot, long now) {
        if (unit.getTarget() != null) return;
        if (!vehicle.hasTurret()) return;
        Vec3 aim = new Vec3(
                spot.getX() - vehicle.getX(),
                spot.getEyeY() - vehicle.getEyeY(),
                spot.getZ() - vehicle.getZ());
        if (aim.lengthSqr() < 1.0E-4) return;
        this.glanceBearing = aim.normalize();
        this.glanceUntil = now + GLANCE_HOLD_TICKS;
        debug("glance hull=#{} until={}", vehicle.getId(), this.glanceUntil);
    }

    /**
     * Coarse occlusion: sample MOTION_BLOCKING heightmap along the horizontal segment.
     * Unloaded sample chunks → not visible. Fence-post false positives are acceptable.
     */
    static boolean coarseVisible(Level level, Vec3 from, Vec3 to, int samples) {
        int n = Mth.clamp(samples, 1, 10);
        for (int i = 1; i <= n; i++) {
            double t = i / (double) (n + 1);
            int x = Mth.floor(from.x + (to.x - from.x) * t);
            int z = Mth.floor(from.z + (to.z - from.z) * t);
            if (!level.hasChunk(x >> 4, z >> 4)) return false;
            int terrainY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            double lineY = from.y + (to.y - from.y) * t;
            if (terrainY > lineY) return false;
        }
        return true;
    }

    private static boolean isCandidate(AbstractUnit unit, VehicleEntity vehicle, LivingEntity e) {
        if (e == unit || !e.isAlive() || !e.isAttackable()) return false;
        if (e.getVehicle() == vehicle) return false;
        // v1: units + players only — not the full Monster zoo at outer range.
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

    static double bandLo(double inner, int band) {
        return inner + band * BAND_WIDTH;
    }

    static double bandHi(double inner, double outer, int band) {
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

    private void dropSpot() {
        this.spotId = -1;
        this.spotPos = null;
        this.spotSeen = Long.MIN_VALUE;
        this.spotDist = Double.MAX_VALUE;
        this.spotBand = -1;
        this.glanceBearing = null;
        this.glanceUntil = Long.MIN_VALUE;
    }

    private static void clearFacts(Facts facts) {
        facts.outerSpotFresh = false;
        facts.outerSpotDist = Double.MAX_VALUE;
        facts.outerSpotStrength = 0.0;
        facts.outerGlanceBearing = null;
        facts.outerGlanceUntil = Long.MIN_VALUE;
    }

    private static void debug(String msg, Object... args) {
        if (!SewvConfig.SPEC.isLoaded() || !SewvConfig.OUTER_RING_DEBUG_LOGGING.get()) return;
        LOG.info("[sewv-outer] " + msg, args);
    }
}
