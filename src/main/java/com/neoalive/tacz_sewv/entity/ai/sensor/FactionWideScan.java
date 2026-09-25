package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.ArrayList;
import java.util.List;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.sensor.CombatantIndex.Entry;

/**
 * Wide detection: one pass per level per cycle over crewed-hull pairs, publishing {@code PROXIMITY}
 * contacts to the {@link ContactBoard} with no raycast at all.
 *
 * <p><b>Pair evaluation, mutual by construction.</b> For every unordered pair the horizontal distance is
 * computed once and both directions are evaluated from it. Hostility is
 * {@link VehicleTargeting#isValidHostileTarget} per direction, per observer <i>unit</i>: invasion team
 * stamps and diplomacy are per unit, so a faction key cannot answer it. There is deliberately <b>no
 * same-faction skip</b>: it would drop every invasion-mode pair (same faction, opposing teams), and no
 * other path publishes those. The vertical band mirrors the close cylinder (extra downward reach for an
 * airborne observer). The min-range dead zone is not applied: that is a lock policy and stays in
 * {@code VehicleTargetScanGoal}; knowing an enemy is close is still a fact.
 *
 * <p><b>Two scopes, independent game-time deadlines.</b> Pairs inside {@code vehicleTargetScanRadius} run at
 * {@code vehicleTargetScanIntervalTicks} (today's close frequency), pairs out to {@code wideScanRadius} at
 * {@code wideScanCadenceTicks}. This pass writes contacts; the close scan reads the index for candidates, so
 * it does not duplicate {@link HullLocalScan}'s fill.
 *
 * <p>Runs from {@link CombatantIndex}'s server tick handler — never from a goal. It needs a snapshot; before
 * the first rebuild it does nothing.
 *
 * <p>Acquisition at range is proximity only. Cheap visual confirmation (a coarse heightmap silhouette) is
 * deliberately deferred; the fire-time gate in {@code MixinVehicleFireCooldown} still needs current LoS.
 */
public final class FactionWideScan {

    private static final Logger LOG = LogUtils.getLogger();

    /** Is this observer allowed to detect this subject? Injected so the pass is testable headless. */
    interface Hostile {
        boolean test(Entry observer, Entry subject);
    }

    /** Write the contact; true if the board actually changed. */
    interface Sink {
        boolean publish(Entry observer, Entry subject);
    }

    static final class PassStats {
        long pairs;
        long predicateCalls;
        long published;
    }

    private static boolean warnedCadenceClamp;

    private FactionWideScan() {}

    // --- pure helpers (ContactBoardSelfCheck-style headless coverage) -----------------------------

    /**
     * A deadline is due when unset, reached, or absurdly far ahead. The last case is game time having gone
     * backwards (world reload, restore): a deadline from the previous world must not stand for hours.
     */
    static boolean due(long now, long next, int interval) {
        return next == Long.MIN_VALUE || now >= next || next - now > interval;
    }

    /**
     * Invariant: a PROXIMITY contact must outlive at least TWO wide cycles, so one missed cycle never
     * lapses it. The configured cadence is clamped to half the PROXIMITY TTL to keep that true.
     */
    static int effectiveWideCadence(int configuredCadence, int ttlBaseTicks) {
        return Math.max(1, Math.min(configuredCadence, ContactBoard.Source.PROXIMITY.ttl(ttlBaseTicks) / 2));
    }

    /** The close scan's vertical band: {@code halfHeight} up, {@code halfHeight + slack} down. */
    static boolean inCylinder(double observerY, double observerSlack, double subjectY, double halfHeight) {
        double dy = subjectY - observerY;
        return dy <= halfHeight && -dy <= halfHeight + observerSlack;
    }

    /**
     * The pair pass. {@code observers} and {@code subjects} must be ascending by id (they come from a
     * {@link CombatantIndex.Snapshot}), which makes call order, and so publish order, deterministic.
     * Each unordered observer/observer pair is evaluated once, from the lower id, in both directions.
     */
    static void evaluate(List<Entry> observers, List<Entry> subjects, double closeRadius, double wideRadius,
                         double halfHeight, boolean closeDue, boolean wideDue,
                         Hostile hostile, Sink sink, PassStats out) {
        double closeSq = closeRadius * closeRadius;
        double wideSq = wideRadius * wideRadius;
        for (Entry o : observers) {
            for (Entry s : subjects) {
                if (s.id == o.id) continue;
                // Both can observe: the pair belongs to whichever has the lower id.
                if (s.canObserve && s.id < o.id) continue;
                double dx = s.x - o.x;
                double dz = s.z - o.z;
                double d2 = dx * dx + dz * dz;
                if (d2 > wideSq) continue;
                if (d2 <= closeSq ? !closeDue : !wideDue) continue;
                out.pairs++;
                if (inCylinder(o.y, o.altitudeSlack, s.y, halfHeight)) {
                    out.predicateCalls++;
                    if (hostile.test(o, s) && sink.publish(o, s)) out.published++;
                }
                if (s.canObserve && inCylinder(s.y, s.altitudeSlack, o.y, halfHeight)) {
                    out.predicateCalls++;
                    if (hostile.test(s, o) && sink.publish(s, o)) out.published++;
                }
            }
        }
    }

    // --- glue --------------------------------------------------------------------------------------

    static void run(ServerLevel level, CombatantIndex.State st, long now) {
        int closeInterval;
        int cadence;
        double closeR;
        double wideR;
        double halfH;
        try {
            if (!SewvConfig.WIDE_SCAN_ENABLED.get() || !SewvConfig.CONTACT_BOARD_ENABLED.get()) return;
            closeInterval = SewvConfig.VEHICLE_TARGET_SCAN_INTERVAL_TICKS.get();
            int configured = SewvConfig.WIDE_SCAN_CADENCE_TICKS.get();
            int ttl = SewvConfig.CONTACT_BOARD_TTL_TICKS.get();
            cadence = effectiveWideCadence(configured, ttl);
            if (cadence < configured && !warnedCadenceClamp) {
                warnedCadenceClamp = true;
                LOG.warn("[sewv] wideScanCadenceTicks={} exceeds half the PROXIMITY contact life derived from "
                        + "contactBoardTtlTicks={}; running at {} so wide contacts never lapse between cycles.",
                        configured, ttl, cadence);
            }
            closeR = SewvConfig.VEHICLE_TARGET_SCAN_RADIUS.get();
            wideR = SewvConfig.WIDE_SCAN_RADIUS.get();
            halfH = SewvConfig.VEHICLE_TARGET_SCAN_HEIGHT.get() / 2.0;
        } catch (Throwable unbaked) {
            return;
        }

        boolean closeDue = due(now, st.nextClosePass, closeInterval);
        boolean wideDue = due(now, st.nextWidePass, cadence);
        if (!closeDue && !wideDue) return;
        if (closeDue) st.nextClosePass = now + closeInterval;
        if (wideDue) st.nextWidePass = now + cadence;

        CombatantIndex.Snapshot snap = st.snapshot;
        if (snap == null || snap.hulls().isEmpty()) return;
        List<Entry> observers = new ArrayList<>();
        for (Entry h : snap.hulls()) if (h.canObserve) observers.add(h);
        if (observers.isEmpty()) return;

        PassStats stats = new PassStats();
        long t0 = System.nanoTime();
        evaluate(observers, snap.subjects(), closeR, Math.max(wideR, closeR), halfH, closeDue, wideDue,
                (o, s) -> o.observer != null && o.observer.isAlive() && s.living != null && s.living.isAlive()
                        && VehicleTargeting.isValidHostileTarget(o.observer, s.living),
                (o, s) -> ContactBoard.publishVetted(o.observer, s.living, ContactBoard.Source.PROXIMITY),
                stats);
        // Per-level (CombatantIndex.State), so a quiet dimension cannot overwrite a busy one's numbers.
        st.widePasses++;
        st.lastPairEvalNanos = System.nanoTime() - t0;
        st.lastContactsPublished = stats.published;
        st.lastPredicateCalls = stats.predicateCalls;
    }
}
