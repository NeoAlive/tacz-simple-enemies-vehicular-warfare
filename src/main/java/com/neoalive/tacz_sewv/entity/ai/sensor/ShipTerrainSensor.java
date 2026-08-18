package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.navigation.VehicleOrca;
import com.neoalive.tacz_sewv.entity.ai.navigation.VehiclePeerSpacing;
import com.neoalive.tacz_sewv.entity.ai.support.WaterSupport;

/**
 * Shoreline sensing for {@link DriveShipGoal}. A grounded ship is a dead ship — SBW gates both
 * thrust and yaw change behind being in fluid, and scales yaw change by current speed, so a hull
 * that beaches has no input left that does anything. Every probe here exists to make sure that
 * never happens.
 *
 * <p>Stricter than the ground sensor in three ways: samples at half-block steps, sweeps the hull's
 * full BEAM (port and starboard offsets, not just the centreline) so a bow that clears doesn't
 * drag a quarter onto a sandbar, and scales reach with current speed since a boat carries way.
 *
 * <p>Peer hulls use the same Optimal Reciprocal Collision Avoidance construction as the ground
 * sensor ({@link VehicleOrca}) rather than a static, velocity-blind AABB — ships previously had no
 * anticipation of a closing course at all, which is a bigger real risk here than on the ground:
 * a ship cannot pivot in place, carries momentum through a turn, and cannot react at the last
 * second the way a tank can. Unlike ground, this is a strict pass/fail folded into
 * {@link #headingClear} — {@link TerrainSensor#chooseClearBearing}'s inherited try-each-offset
 * loop has no graded score to feed, so there is no soft/skirt tier here, only the hard cutoff.
 * That cutoff is a MARGIN THRESHOLD, not the raw geometric permitted/forbidden boundary: verified
 * numerically before this was wired up, {@code margin(vOptSelf,...) < 0} whenever ANY peer is in
 * range at all (continuing at the exact current velocity is structurally never fully "free" of
 * restriction once a peer's half-plane is nonzero) — a raw {@code margin>=0} gate rejected every
 * one of the whisker fan's candidate headings for something as mundane as two ships in a loose
 * convoy with a few degrees of heading difference. {@link #ORCA_HARD_MARGIN} is picked so mundane,
 * well-separated, near-parallel traffic stays fully open while a genuinely close approach still
 * narrows down to its evasive headings.
 */
public final class ShipTerrainSensor extends TerrainSensor {

    private static final double LOOKAHEAD_DISTANCE = 5.0;
    private static final double SAMPLE_STEP = 1.0;
    /** Extra reach per block/tick of current speed. */
    private static final double SPEED_LOOKAHEAD = 24.0;
    private static final double MIN_REACH = 6.0;

    /** ORCA time horizon in ticks. Meaningfully larger than ground's 30: a ship cannot react at
     * the last second, has no pivot, and carries momentum through a turn, so avoidance needs to
     * start well before a ground vehicle's equivalent would. Seed value — tune live. */
    private static final double ORCA_TAU = 55.0;
    /** Margin (blocks/tick) of half-plane penetration beyond which a heading is rejected. See the
     * class doc — this is deliberately NOT zero. Seed value — tune live. */
    private static final double ORCA_HARD_MARGIN = 0.15;
    /** Preferred speed used when the hull is nearly stopped, so ORCA still sees incoming traffic. */
    private static final double MIN_PREF_SPEED = 0.1;
    /** Tiny bias to deterministically and reciprocally break the one real degeneracy in the
     * half-plane construction — see VehicleOrca#halfPlane. */
    private static final double ORCA_TIE_EPS = 1.0E-6;

    private List<VehicleOrca.Peer> peers = List.of();
    private long lastBlockedDiagTick = Long.MIN_VALUE;

    public ShipTerrainSensor(AbstractUnit unit) {
        super(unit);
    }

    public boolean enabled() {
        return SewvConfig.VEHICLE_TERRAIN_AVOIDANCE.get();
    }

    /** Base look-ahead plus a stopping-distance allowance for the speed actually being carried. */
    public double lookahead() {
        double speed = this.vehicle == null ? 0.0 : this.vehicle.getDeltaMovement().horizontalDistance();
        return Math.max(MIN_REACH, LOOKAHEAD_DISTANCE + speed * SPEED_LOOKAHEAD);
    }

    public Vec3 chooseClearBearing(Vec3 desired) {
        return chooseClearBearing(desired, lookahead());
    }

    @Override
    public boolean headingClear(Vec3 dir, double distance) {
        Level level = this.unit.level();
        double half = halfWidth();
        // Probe the beam, not a line: a ship is wide and turns by swinging its stern out.
        double beam = half + 0.5;
        int y = this.vehicle.getBlockY();
        Vec3 side = new Vec3(-dir.z, 0.0, dir.x);
        obstacles(distance); // triggers buildObstacles, which populates this.peers as a side effect

        for (double d = half + 0.5; d <= half + distance; d += SAMPLE_STEP) {
            double cx = this.vehicle.getX() + dir.x * d;
            double cz = this.vehicle.getZ() + dir.z * d;
            for (double lateral = -beam; lateral <= beam; lateral += beam) {
                double x = cx + side.x * lateral;
                double z = cz + side.z * lateral;
                if (!WaterSupport.floatableAt(level, Mth.floor(x), y, Mth.floor(z))) return false;
            }
        }
        // One ORCA check per candidate heading, not per sample point — mirrors the ground
        // sensor's own split of "terrain per point, moving-peer check once per candidate".
        if (!orcaClear(dir)) {
            logBlockedHeading(dir, distance);
            return false;
        }
        return true;
    }

    private boolean orcaClear(Vec3 dir) {
        if (this.peers.isEmpty()) return true;
        double speed = Math.max(MIN_PREF_SPEED, this.vehicle.getDeltaMovement().horizontalDistance());
        double candX = dir.x * speed;
        double candZ = dir.z * speed;
        Vec3 v = this.vehicle.getDeltaMovement();
        double ax = v.x;
        double az = v.z;
        double half = halfWidth();
        double selfX = this.vehicle.getX();
        double selfZ = this.vehicle.getZ();
        int selfId = this.vehicle.getId();
        for (VehicleOrca.Peer peer : this.peers) {
            double radius = VehicleOrca.radius(half, peer.half());
            double px = peer.x() - selfX;
            double pz = peer.z() - selfZ;
            if (VehicleOrca.overlappingAndClosing(px, pz, candX, candZ, ax, az, peer.vx(), peer.vz(), radius)) {
                return false;
            }
            double sideBias = ORCA_TIE_EPS * Integer.signum(selfId - peer.id());
            VehicleOrca.HalfPlane hp = VehicleOrca.halfPlane(
                    px, pz, ax, az, peer.vx(), peer.vz(), radius, ORCA_TAU, sideBias);
            double margin = VehicleOrca.margin(candX, candZ, ax, az, hp);
            if (margin < -ORCA_HARD_MARGIN) return false;
        }
        return true;
    }

    @Override
    protected List<AABB> buildObstacles(double reach) {
        double half = halfWidth();
        // Same ORCA culling guidance as the ground sensor (paper §5.1): a peer farther than
        // (selfSpeed+peerSpeed)*tau can never collide within the horizon. Peer speed is
        // approximated as self speed absent a reliable per-hull max-speed source.
        double selfSpeed = this.vehicle.getDeltaMovement().horizontalDistance();
        double orcaReach = Math.max(LOOKAHEAD_DISTANCE, 2.0 * selfSpeed * ORCA_TAU);
        double range = Math.max(reach, orcaReach) + half + 1.0;
        AABB search = this.vehicle.getBoundingBox().inflate(range, 2.0, range);
        this.peers = this.unit.level().getEntitiesOfClass(VehicleEntity.class, search,
                        v -> v != this.vehicle && VehiclePeerSpacing.isPeer(this.vehicle, this.unit, v)).stream()
                .map(v -> {
                    Vec3 vel = v.getDeltaMovement();
                    return new VehicleOrca.Peer(v.getId(), v.getX(), v.getZ(), vel.x, vel.z, v.getBbWidth() * 0.5);
                })
                .toList();
        return List.of();
    }

    private void logBlockedHeading(Vec3 dir, double distance) {
        if (!SewvDiag.shipPathingVerbose()) return;
        long now = this.unit.level().getGameTime();
        if (now == this.lastBlockedDiagTick) return;
        this.lastBlockedDiagTick = now;
        SewvDiag.ship(
                "headingClear BLOCKED unit={}#{} vehicle={}#{} reason=hull dir={} distance={} peers={}",
                this.unit.getClass().getSimpleName(), this.unit.getId(),
                this.vehicle.getName().getString(), this.vehicle.getId(),
                dir, distance, this.peers.size());
    }

}
