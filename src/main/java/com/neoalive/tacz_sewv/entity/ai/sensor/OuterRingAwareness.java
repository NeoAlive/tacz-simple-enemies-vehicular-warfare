package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.sensor.CombatantIndex.Entry;

/**
 * Outer awareness ring for ground crews: hostiles between the mounted target-scan radius and the outer
 * max, plus foliage-obscured contacts offered from the inner scan.
 *
 * <p><b>Runs on the {@link CombatantIndex} snapshot</b>, once per rebuild, from its server tick — never
 * per hull. Hull/mortar-crew pairs go through {@link FactionWideScan#evaluate} (one distance, both
 * directions); infantry and players through the snapshot grid. <b>No line of sight at all</b>: a ring
 * spot is awareness, and the fire-time gate still needs LoS.
 *
 * <p><b>Never</b> calls {@code setTarget}. The nearest spot per hull is parked in {@link #SPOT_OFFERS}
 * and consumed by the hull's {@link #tick}, which hands it to {@link AwarenessCues}.
 */
public final class OuterRingAwareness {

    private static final Logger LOG = LogUtils.getLogger();

    private static final int BANDS = 4;
    /** Two chunks of radial width per band (except the edge band, which runs to outer max). */
    private static final double BAND_WIDTH = 32.0;
    /** Strength offered to {@link AwarenessCues} by band (near → edge). */
    private static final double[] BAND_STRENGTH = {1.0, 0.75, 0.5, 0.25};

    /** Foliage-only contacts from the mounted cylinder scan, keyed by hull id; consumed on the next {@link #tick}. */
    private static final ConcurrentHashMap<Integer, Offer> FOLIAGE_OFFERS = new ConcurrentHashMap<>();
    /** Outer-ring spots from the snapshot pass, keyed by hull id; consumed on the next {@link #tick}. */
    private static final ConcurrentHashMap<Integer, Offer> SPOT_OFFERS = new ConcurrentHashMap<>();

    private VehicleEntity hull;

    public void clear() {
        if (this.hull != null) forget(this.hull.getId());
        this.hull = null;
    }

    /** Drop pending offers for a hull that left the level without {@link #clear()} running. */
    public static void forget(int hullId) {
        FOLIAGE_OFFERS.remove(hullId);
        SPOT_OFFERS.remove(hullId);
    }

    /**
     * Inner-cylinder contact visible only through leaves — not engageable, but something is
     * there. Reuses the {@link AwarenessCues} investigate path.
     */
    public static void offerFoliageContact(VehicleEntity hull, LivingEntity contact) {
        if (hull == null || contact == null || !contact.isAlive()) return;
        double dx = contact.getX() - hull.getX();
        double dz = contact.getZ() - hull.getZ();
        FOLIAGE_OFFERS.put(hull.getId(),
                new Offer(contact.getId(), contact.blockPosition(), Math.sqrt(dx * dx + dz * dz), BAND_STRENGTH[0]));
    }

    /** Consume this hull's pending offers into {@link AwarenessCues}. Call before {@link AwarenessCues#tick}. */
    public void tick(AbstractUnit unit, VehicleEntity vehicle, AwarenessCues cues, boolean underOrders) {
        if (this.hull != vehicle) {
            clear();
            this.hull = vehicle;
        }
        Offer foliage = FOLIAGE_OFFERS.remove(vehicle.getId());
        Offer spot = SPOT_OFFERS.remove(vehicle.getId());
        if (underOrders || unit.getTarget() != null) return;

        long now = unit.level().getGameTime();
        consume(unit, vehicle, cues, foliage, now, "foliage");
        consume(unit, vehicle, cues, spot, now, "spot");
    }

    private static void consume(AbstractUnit unit, VehicleEntity vehicle, AwarenessCues cues, Offer offer,
            long now, String what) {
        if (offer == null) return;
        Entity e = unit.level().getEntity(offer.id);
        if (!(e instanceof LivingEntity living) || !living.isAlive()) return;
        cues.offerEntitySpot(offer.id, offer.pos, offer.dist, offer.strength, now, living);
        debug("{} hull=#{} cand=#{} dist={}", what, vehicle.getId(), offer.id, offer.dist);
    }

    // --- snapshot pass --------------------------------------------------------------------------

    /** Nearest hostile subject per observer hull id. */
    record Best(Entry subject, double distSq) {}

    /**
     * Pure pair half: nearest hostile hull/mortar-crew subject per observer in the {@code inner..outer}
     * annulus, mutual from one distance. Headless-testable (see {@code OuterRingAwarenessSelfCheck}).
     */
    static Map<Integer, Best> nearestPairs(List<Entry> observers, List<Entry> subjects, double inner,
            double outer, double halfH, double groundUp, FactionWideScan.Hostile hostile) {
        Map<Integer, Best> best = new HashMap<>();
        FactionWideScan.evaluate(observers, subjects, inner, outer, halfH, groundUp, false, true, hostile,
                (o, s) -> {
                    keep(best, o, s);
                    return false;
                }, new FactionWideScan.PassStats());
        return best;
    }

    private static void keep(Map<Integer, Best> best, Entry o, Entry s) {
        double dx = s.x - o.x;
        double dz = s.z - o.z;
        double d2 = dx * dx + dz * dz;
        Best cur = best.get(o.id);
        if (cur == null || d2 < cur.distSq) best.put(o.id, new Best(s, d2));
    }

    /** Called from {@link CombatantIndex#onServerTick} right after a rebuild. */
    static void runPass(ServerLevel level, CombatantIndex.Snapshot snap) {
        double inner;
        double outer;
        double halfH;
        double groundUp;
        try {
            if (!SewvConfig.OUTER_RING_ENABLED.get()) return;
            inner = SewvConfig.VEHICLE_TARGET_SCAN_RADIUS.get();
            outer = Math.min(SewvConfig.OUTER_RING_MAX_BLOCKS.get(),
                    level.getServer().getPlayerList().getSimulationDistance() * 16.0);
            halfH = VehicleScanBand.halfHeight();
            groundUp = VehicleScanBand.groundUpward();
        } catch (Throwable unbaked) {
            return;
        }
        if (outer <= inner || snap.hulls().isEmpty()) return;

        List<Entry> observers = new java.util.ArrayList<>();
        for (Entry h : snap.hulls()) if (h.canObserve) observers.add(h);
        if (observers.isEmpty()) return;

        FactionWideScan.Hostile hostile = OuterRingAwareness::isCandidate;
        Map<Integer, Best> best = nearestPairs(observers, snap.subjects(), inner, outer, halfH, groundUp, hostile);

        double innerSq = inner * inner;
        double outerSq = outer * outer;
        for (Entry o : observers) {
            double up = o.altitudeSlack > 0 ? halfH : groundUp;
            for (Entry s : snap.query(o.x, o.z, outer, o.y - halfH - o.altitudeSlack, o.y + up)) {
                if (s.kind != CombatantIndex.Kind.UNIT && !(s.kind == CombatantIndex.Kind.SOFT
                        && s.living instanceof Player)) {
                    continue;
                }
                double dx = s.x - o.x;
                double dz = s.z - o.z;
                double d2 = dx * dx + dz * dz;
                if (d2 <= innerSq || d2 > outerSq) continue;
                if (!isCandidate(o, s)) continue;
                keep(best, o, s);
            }
        }

        for (Entry o : observers) {
            Best b = best.get(o.id);
            if (b == null) continue;
            double dist = Math.sqrt(b.distSq);
            LivingEntity living = b.subject.living;
            SPOT_OFFERS.put(o.id, new Offer(living.getId(), living.blockPosition(), dist,
                    strengthAt(inner, outer, dist)));
            // A ring spot is exactly "aware, no line of sight": the board's PROXIMITY tier.
            ContactBoard.publish(o.observer, living, ContactBoard.Source.PROXIMITY);
            debug("pass hull=#{} spotted=#{} dist={}", o.id, living.getId(), dist);
        }
    }

    private static boolean isCandidate(Entry o, Entry s) {
        AbstractUnit unit = o.observer;
        LivingEntity e = s.living;
        if (unit == null || e == null || !unit.isAlive()) return false;
        if (e == unit || !e.isAlive() || !e.isAttackable()) return false;
        if (unit.getVehicle() != null && e.getVehicle() == unit.getVehicle()) return false;
        if (!(e instanceof AbstractUnit || e instanceof Player)) return false;
        if (e instanceof Player p && (p.isCreative() || p.isSpectator())) return false;
        return !VehicleTargeting.isNonHostile(unit, e);
    }

    /** Band strength for a horizontal distance in the annulus. */
    static double strengthAt(double inner, double outer, double dist) {
        for (int b = 0; b < BANDS; b++) {
            if (dist <= bandHi(inner, outer, b)) return BAND_STRENGTH[b];
        }
        return BAND_STRENGTH[BANDS - 1];
    }

    public static double bandLo(double inner, int band) {
        return inner + band * BAND_WIDTH;
    }

    public static double bandHi(double inner, double outer, int band) {
        if (band >= BANDS - 1) return outer;
        return inner + (band + 1) * BAND_WIDTH;
    }

    private static void debug(String msg, Object... args) {
        if (!ClientConfig.flag(ClientConfig.OUTER_RING_DEBUG_LOGGING)) return;
        LOG.info("[sewv-outer] " + msg, args);
    }

    private record Offer(int id, BlockPos pos, double dist, double strength) {}
}
