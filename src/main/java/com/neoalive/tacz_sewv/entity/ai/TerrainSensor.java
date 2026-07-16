package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.List;

/**
 * "Can this hull travel that way?" — the shared shape of the ground
 * ({@link GroundTerrainSensor}) and airborne ({@link AirTerrainSensor}) obstacle sensors.
 *
 * <p>What both do the same way, and what lives here:
 *
 * <ul>
 * <li><b>The whisker fan.</b> Probe the bearing we actually want; if it is blocked, fan out
 *     to alternating flanks and take the smallest deflection that is clear. A fully blocked
 *     forward cone answers null, which each goal handles in the way its medium allows —
 *     a ground hull turns in place at the edge, an aircraft climbs over.</li>
 * <li><b>The entity obstacle cache.</b> Other hulls are entities, so no block probe can see
 *     them; they have to be queried and their boxes pre-inflated by our own half-width, so a
 *     centerline point probe stands in for sweeping the full hull width along the bearing.
 *     The fan probes up to seven headings a tick and every one consults the list, so it is
 *     built at most once per tick — and rebuilt within a tick only if a longer probe than
 *     the one it was built for comes along, rather than silently missing traffic near the
 *     far end of the fan.</li>
 * </ul>
 *
 * <p>What each medium answers for itself is {@link #headingClear} (what counts as a hazard)
 * and {@link #buildObstacles} (which hulls count, and how much room they need).
 */
abstract class TerrainSensor {

    /** Preferred bearing first, then alternating flanks at growing deflection. */
    private static final double[] WHISKER_OFFSETS_DEG = {0.0, 25.0, -25.0, 50.0, -50.0, 75.0, -75.0};

    protected final AbstractUnit unit;
    protected VehicleEntity vehicle;

    private long obstacleCacheTime = Long.MIN_VALUE;
    private double obstacleCacheReach = -1.0;
    private List<AABB> obstacleCache = List.of();

    protected TerrainSensor(AbstractUnit unit) {
        this.unit = unit;
    }

    /** Keyed on identity, so calling it every tick costs one comparison. */
    final void attach(VehicleEntity v) {
        if (this.vehicle == v) return;
        this.vehicle = v;
        onAttach(v);
        dropCache();
    }

    final void clear() {
        this.vehicle = null;
        dropCache();
    }

    /** Hook for per-hull facts a subclass resolves once rather than per probe. */
    protected void onAttach(VehicleEntity v) {}

    /**
     * The nearest clear bearing to {@code desired}, or null when the whole forward cone is
     * blocked. A zero-length request (already on the point) is passed straight back.
     */
    final Vec3 chooseClearBearing(Vec3 desired, double probeDistance) {
        if (desired.lengthSqr() < 1.0E-8) return desired;
        for (double offDeg : WHISKER_OFFSETS_DEG) {
            Vec3 candidate = VehicleTargeting.rotateY(desired, Math.toRadians(offDeg));
            if (headingClear(candidate, probeDistance)) return candidate;
        }
        return null;
    }

    /** True when travelling {@code distance} blocks along {@code dir} crosses nothing to avoid. */
    abstract boolean headingClear(Vec3 dir, double distance);

    /** Every hull to steer around, each box already inflated by the room we need beside it. */
    protected abstract List<AABB> buildObstacles(double reach);

    protected final List<AABB> obstacles(double reach) {
        long now = this.unit.level().getGameTime();
        if (now != this.obstacleCacheTime || reach > this.obstacleCacheReach) {
            this.obstacleCacheTime = now;
            this.obstacleCacheReach = reach;
            this.obstacleCache = buildObstacles(reach);
        }
        return this.obstacleCache;
    }

    protected final double halfWidth() {
        return this.vehicle.getBbWidth() / 2.0;
    }

    private void dropCache() {
        this.obstacleCacheTime = Long.MIN_VALUE;
        this.obstacleCacheReach = -1.0;
        this.obstacleCache = List.of();
    }
}
