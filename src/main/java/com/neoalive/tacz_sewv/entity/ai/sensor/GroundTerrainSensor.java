package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.compat.EnhancedFallingTreesCompat;
import com.neoalive.tacz_sewv.compat.EnhancedFallingTreesFeller;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.debug.PathingPerf;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.navigation.GroundMobility;
import com.neoalive.tacz_sewv.entity.ai.navigation.VehicleOrca;
import com.neoalive.tacz_sewv.entity.ai.navigation.VehiclePeerSpacing;

/**
 * Terrain sensing for a ground hull: context maps over the same 7-slot fan the old
 * boolean whiskers used. Each slot gets an interest vote (toward the waypoint) and a
 * danger vote (terrain / hull / ally); merge is strongest-wins per slot, never summed.
 * The heading is interpolated around the winner, then beam-validated.
 *
 * <p>The {@code amphibious} flag only ever RELAXES the water hazard. Land is never a
 * hazard here — an amphibious APC still drives on land.
 *
 * <p>Peer hulls use Optimal Reciprocal Collision Avoidance (not a frozen AABB along the heading):
 * each crew's half-plane construction assumes the other takes half the dodge, which is what
 * stops two tanks swapping sides every tick. Infantry on foot stay an AABB. Terrain stays on the
 * maps.
 */
public final class GroundTerrainSensor extends TerrainSensor {

    private static final double LOOKAHEAD_DISTANCE = 5.0;
    private static final int FLUID_PROBE_DEPTH = 8;
    private static final int N = GroundMobility.SLOT_COUNT;
    /** Preferred speed used when the hull is nearly stopped, so ORCA still sees incoming traffic. */
    private static final double MIN_PREF_SPEED = 0.2;

    /** ORCA time horizon in ticks — collisions beyond this are not anticipated. Matches the old
     * GroundRvo.TAU value this replaces (behavior-parity seed, not re-derived). */
    private static final double ORCA_TAU = 30.0;
    /** Ticks-to-impact under the reciprocal relative velocity at or below which a candidate is
     * hard-blocked outright — the decisive stop {@link VehicleOrca#margin} cannot provide on its
     * own (penetration depth saturates rather than diverging as contact nears; verified
     * numerically that continuing straight at a stationary wreck 6 blocks away, barely more than
     * a tank pair's combined radius, never crossed a 0.2 hard-margin threshold at all). Matches
     * the old GroundRvo.IMMINENT value this restores. */
    private static final double ORCA_IMMINENT_TICKS = 8.0;
    /** Scale (blocks/tick) for the graded skirt ramp: skirt = penetration / this, clamped under
     * HARD_CAP. Not a hard threshold — {@link #ORCA_IMMINENT_TICKS} (plus overlap-and-closing) is
     * the sole hard trigger; half-plane penetration only ever produces graded PREFERENCE here.
     * Verified numerically this split matters: penetration depth saturates around ±0.3 blocks/tick
     * even at long range for a wide-angle turn away from a peer 40+ blocks distant, so using it as
     * a hard threshold too produced false "must stop" blocks nowhere near an actual collision.
     * Seeded to roughly the old TAU=30-tick RVO ramp feel at cruise speed — tune live. */
    private static final double ORCA_SKIRT_SCALE = 0.2;
    /** Tiny bias to deterministically and reciprocally break the one real degeneracy in the
     * half-plane construction: a perfectly symmetric head-on approach has no basis to prefer a
     * side. See VehicleOrca#halfPlane. */
    private static final double ORCA_TIE_EPS = 1.0E-6;

    private boolean amphibious;
    private float maxUpStep = 1.0F;
    private int hullTop = 1;

    private long lastBlockedDiagTick = Long.MIN_VALUE;
    private long lastSteerDiagTick = Long.MIN_VALUE;

    private long centerCacheTick = Long.MIN_VALUE;
    private int centerCacheColX;
    private int centerCacheColZ;
    private int centerCacheBaseY;
    private double cachedCenterFloor = GroundMobility.NO_FLOOR;
    private int cachedCenterWater;

    private final float[] interest = new float[N];
    private final float[] hardDanger = new float[N];
    private final float[] peerSkirt = new float[N];
    private final float[] prevInterest = new float[N];
    private final float[] prevHard = new float[N];
    private final float[] prevSkirt = new float[N];
    private final String[] slotReason = new String[N];
    private boolean mapsPrimed;

    private boolean lastFanHullDominated;
    private String lastFanReasons = "";
    private int lastSlot = Integer.MIN_VALUE;
    private double lastStepDelta;
    private int lastWaterDepth;

    private List<AABB> allyFootObstacles = List.of();
    private List<VehicleOrca.Peer> peers = List.of();

    public GroundTerrainSensor(AbstractUnit unit) {
        super(unit);
    }

    @Override
    protected void onAttach(VehicleEntity v) {
        this.amphibious = GroundMobility.isAmphibious(v);
        this.maxUpStep = GroundMobility.maxUpStepOf(v);
        this.hullTop = Math.max(1, Mth.ceil(v.getBbHeight()) - 1);
        this.centerCacheTick = Long.MIN_VALUE;
        this.mapsPrimed = false;
        this.lastFanHullDominated = false;
        this.lastFanReasons = "";
        this.lastSlot = Integer.MIN_VALUE;
        this.allyFootObstacles = List.of();
        this.peers = List.of();
    }

    /**
     * Center column is over-ford-depth water while SBW still says dry — the bank-overhang
     * case. Map blend covers heading flicker; reverse recovery still wants this boolean.
     */
    public boolean isDryBankLipHazard() {
        return this.vehicle != null
                && !this.amphibious
                && !this.vehicle.isInWater()
                && this.cachedCenterWater > GroundMobility.FORD_DEPTH;
    }

    public boolean isLastFanHullDominated() {
        return this.lastFanHullDominated;
    }

    public String lastFanReasons() {
        return this.lastFanReasons;
    }

    public boolean enabled() {
        return SewvConfig.VEHICLE_TERRAIN_AVOIDANCE.get();
    }

    public double lookahead() {
        return LOOKAHEAD_DISTANCE;
    }

    public Vec3 chooseClearBearing(Vec3 desired) {
        return chooseClearBearing(desired, lookahead());
    }

    @Override
    public Vec3 chooseClearBearing(Vec3 desired, double probeDistance) {
        return chooseClearBearing(desired, probeDistance, false);
    }

    @Override
    public Vec3 chooseClearBearing(Vec3 desired, double probeDistance, boolean stuck) {
        if (desired.lengthSqr() < 1.0E-8) return desired;
        long t0 = System.nanoTime();
        Vec3 result = pickFromMaps(desired, probeDistance);
        PathingPerf.fanNanos += System.nanoTime() - t0;
        PathingPerf.fanCalls++;
        return result;
    }

    private Vec3 pickFromMaps(Vec3 desired, double probeDistance) {
        this.lastFanHullDominated = false;
        this.lastFanReasons = "";
        ensureCenter();

        boolean[] reachable = fillMaps(desired, probeDistance);
        int winner = GroundMobility.pickWinner(this.interest, this.hardDanger, this.peerSkirt, reachable);
        while (winner >= 0) {
            Vec3 candidate = VehicleTargeting.rotateY(desired, Math.toRadians(GroundMobility.SLOTS_DEG[winner]));
            Probe sample = probeHeading(candidate, probeDistance, true);
            if (sample.hard < GroundMobility.HARD_CAP) {
                double offset = GroundMobility.interpolateOffsetDeg(
                        winner, this.interest, this.hardDanger, this.peerSkirt);
                Vec3 heading = VehicleTargeting.rotateY(desired, Math.toRadians(offset));
                noteWinner(winner, sample);
                logSteerTick(desired, winner, offset, sample, heading);
                return heading;
            }
            reachable[winner] = false;
            winner = GroundMobility.pickWinner(this.interest, this.hardDanger, this.peerSkirt, reachable);
        }
        noteFullBlock();
        return null;
    }

    private boolean[] fillMaps(Vec3 desired, double probeDistance) {
        var forward = this.vehicle.getForwardDirection().normalize();
        int facingSlot = 0;
        double bestFacing = Double.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            Vec3 dir = VehicleTargeting.rotateY(desired, Math.toRadians(GroundMobility.SLOTS_DEG[i]));
            Probe p = probeHeading(dir, probeDistance, false);
            this.hardDanger[i] = p.hard;
            this.peerSkirt[i] = p.skirt;
            this.slotReason[i] = p.reason;
            this.interest[i] = GroundMobility.goalInterest(GroundMobility.SLOTS_DEG[i]);
            double facingErr = Math.abs(VehicleTargeting.signedAngleTo(forward, dir));
            if (facingErr < bestFacing) {
                bestFacing = facingErr;
                facingSlot = i;
            }
        }
        this.interest[facingSlot] = Math.max(this.interest[facingSlot], GroundMobility.FACING_INTEREST);
        if (this.mapsPrimed) {
            GroundMobility.blendMaps(this.prevInterest, this.interest);
            GroundMobility.blendMaps(this.prevHard, this.hardDanger);
            GroundMobility.blendMaps(this.prevSkirt, this.peerSkirt);
        }
        this.mapsPrimed = true;
        System.arraycopy(this.interest, 0, this.prevInterest, 0, N);
        System.arraycopy(this.hardDanger, 0, this.prevHard, 0, N);
        System.arraycopy(this.peerSkirt, 0, this.prevSkirt, 0, N);
        return GroundMobility.reachableMask(this.hardDanger, facingSlot);
    }

    private void noteWinner(int slot, Probe sample) {
        if (this.lastSlot != Integer.MIN_VALUE && slot != this.lastSlot) PathingPerf.slotFlips++;
        this.lastSlot = slot;
        this.lastStepDelta = sample.stepDelta;
        this.lastWaterDepth = sample.waterDepth;
        this.lastFanHullDominated = false;
        this.lastFanReasons = "";
    }

    private void noteFullBlock() {
        int n = 0;
        int hull = 0;
        StringBuilder reasons = new StringBuilder();
        for (int i = 0; i < N; i++) {
            if (this.hardDanger[i] < GroundMobility.HARD_CAP) continue;
            if (n > 0) reasons.append(',');
            reasons.append(this.slotReason[i]);
            if ("hull".equals(this.slotReason[i])) hull++;
            n++;
        }
        this.lastFanHullDominated = n > 0 && hull * 2 > n;
        this.lastFanReasons = reasons.toString();
        if (this.lastSlot != Integer.MIN_VALUE) PathingPerf.slotFlips++;
        this.lastSlot = -1;
        SewvDiag.pathingEvent(
                "fan BLOCKED unit={}#{} vehicle={}#{} offsets={} reasons=[{}] "
                        + "hull={}/{} hullDominated={} rule=hullCount*2>n",
                this.unit.getClass().getSimpleName(), this.unit.getId(),
                this.vehicle.getName().getString(), this.vehicle.getId(),
                n, this.lastFanReasons, hull, n, this.lastFanHullDominated);
    }

    @Override
    public boolean headingClear(Vec3 dir, double distance) {
        ensureCenter();
        Probe p = probeHeading(dir, distance, false);
        this.lastStepDelta = p.stepDelta;
        this.lastWaterDepth = p.waterDepth;
        if (p.hard >= GroundMobility.HARD_CAP) {
            logBlockedHeading(dir, distance, p.reason);
            return false;
        }
        return true;
    }

    private Probe probeHeading(Vec3 dir, double distance, boolean beam) {
        double half = halfWidth();
        Vec3 side = new Vec3(-dir.z, 0.0, dir.x);
        Probe p;
        if (!beam) {
            p = probeLine(dir, 0.0, distance, side);
        } else {
            p = new Probe();
            for (int k = -1; k <= 1; k++) {
                Probe sample = probeLine(dir, k * half, distance, side);
                if (sample.hard >= GroundMobility.HARD_CAP) {
                    p = sample;
                    break;
                }
                if (sample.hard > p.hard) p = sample;
                else p.skirt = Math.max(p.skirt, sample.skirt);
            }
        }
        if (p.hard < GroundMobility.HARD_CAP) applyRvo(dir, p);
        return p;
    }

    private Probe probeLine(Vec3 dir, double lateral, double distance, Vec3 side) {
        Probe out = new Probe();
        Level level = this.unit.level();
        double startX = this.vehicle.getX();
        double startZ = this.vehicle.getZ();
        double half = halfWidth();
        obstacles(distance);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double floor = this.cachedCenterFloor;
        boolean crossedDrop = false;

        for (double d = half + 0.5; d <= half + distance; d += 1.0) {
            double sampleX = startX + dir.x * d + side.x * lateral;
            double sampleZ = startZ + dir.z * d + side.z * lateral;
            if (isBlockedByHull(this.allyFootObstacles, sampleX, sampleZ)) {
                out.hard = 1.0F;
                out.reason = "ally";
                return out;
            }

            Column col = probeColumn(level, pos, Mth.floor(sampleX), Mth.floor(sampleZ));
            out.waterDepth = Math.max(out.waterDepth, col.waterDepth);
            if (col.lava) {
                out.hard = 1.0F;
                out.reason = "fluid";
                return out;
            }
            float water = GroundMobility.waterDanger(col.waterDepth, this.amphibious, this.vehicle.isInWater());
            if (water >= GroundMobility.HARD_CAP) {
                out.hard = 1.0F;
                out.reason = "fluid";
                return out;
            }
            out.hard = Math.max(out.hard, water);

            if (col.floorY == GroundMobility.NO_FLOOR) {
                crossedDrop = true;
                continue;
            }
            if (crossedDrop) {
                floor = col.floorY;
                crossedDrop = false;
                continue;
            }
            if (floor != GroundMobility.NO_FLOOR) {
                double step = col.floorY - floor;
                out.stepDelta = step;
                if (col.tree) {
                    // Prefer around it, never hard-block: TreeFellingSupport clears it on real
                    // contact, so a whisker treating it as an ordinary wall would steer the hull
                    // away before it ever touches the tree it is meant to drive through.
                    float treeDanger = SewvConfig.VEHICLE_TREE_SENSOR_DANGER.get().floatValue();
                    if (treeDanger > out.hard) {
                        out.hard = treeDanger;
                        out.reason = "tree";
                    }
                } else {
                    float stepD = GroundMobility.stepDanger(step, this.maxUpStep);
                    if (stepD >= GroundMobility.HARD_CAP) {
                        out.hard = 1.0F;
                        out.reason = "step";
                        return out;
                    }
                    if (stepD > out.hard) {
                        out.hard = stepD;
                        out.reason = "step";
                    }
                }
            }
            floor = col.floorY;
        }
        return out;
    }

    private void applyRvo(Vec3 dir, Probe p) {
        if (this.peers.isEmpty()) return;
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
            if (VehicleOrca.overlappingAndClosing(px, pz, candX, candZ, ax, az, peer.vx(), peer.vz(), radius)
                    || VehicleOrca.imminent(px, pz, candX, candZ, ax, az, peer.vx(), peer.vz(), radius, ORCA_IMMINENT_TICKS)) {
                p.hard = 1.0F;
                p.reason = "hull";
                return;
            }
            double sideBias = ORCA_TIE_EPS * Integer.signum(selfId - peer.id());
            VehicleOrca.HalfPlane hp = VehicleOrca.halfPlane(
                    px, pz, ax, az, peer.vx(), peer.vz(), radius, ORCA_TAU, sideBias);
            double margin = VehicleOrca.margin(candX, candZ, ax, az, hp);
            if (margin < 0.0) {
                float skirt = Math.min(0.99F, (float) (-margin / ORCA_SKIRT_SCALE));
                p.skirt = Math.max(p.skirt, skirt);
            }
        }
    }

    private void ensureCenter() {
        Level level = this.unit.level();
        long now = level.getGameTime();
        int colX = Mth.floor(this.vehicle.getX());
        int colZ = Mth.floor(this.vehicle.getZ());
        int baseY = this.vehicle.getBlockY();
        if (now == this.centerCacheTick
                && colX == this.centerCacheColX
                && colZ == this.centerCacheColZ
                && baseY == this.centerCacheBaseY) {
            return;
        }
        Column col = probeColumn(level, new BlockPos.MutableBlockPos(), colX, colZ);
        this.centerCacheTick = now;
        this.centerCacheColX = colX;
        this.centerCacheColZ = colZ;
        this.centerCacheBaseY = baseY;
        this.cachedCenterFloor = col.floorY;
        this.cachedCenterWater = col.waterDepth;
    }

    private Column probeColumn(Level level, BlockPos.MutableBlockPos pos, int x, int z) {
        Column col = new Column();
        int baseY = this.vehicle.getBlockY();
        col.waterDepth = GroundMobility.waterDepth(level, pos, x, baseY, z);
        for (int dy = 0; dy >= -1; dy--) {
            FluidState fluid = level.getFluidState(pos.set(x, baseY + dy, z));
            if (fluid.is(FluidTags.LAVA)) {
                col.lava = true;
                return col;
            }
        }
        for (int y = baseY + this.hullTop; y >= baseY - FLUID_PROBE_DEPTH; y--) {
            var state = level.getBlockState(pos.set(x, y, z));
            if (state.getFluidState().is(FluidTags.LAVA)) {
                col.lava = true;
                return col;
            }
            var shape = state.getCollisionShape(level, pos);
            if (!shape.isEmpty()) {
                col.floorY = y + shape.max(Direction.Axis.Y);
                if (EnhancedFallingTreesCompat.available()
                        && (EnhancedFallingTreesFeller.isFellable(level, pos, state)
                                || EnhancedFallingTreesFeller.isFoliage(state))) {
                    col.tree = true;
                }
                return col;
            }
            if (this.amphibious && state.getFluidState().is(FluidTags.WATER)) {
                col.floorY = y + 1.0;
                return col;
            }
        }
        return col;
    }

    private void logSteerTick(Vec3 desired, int slot, double offset, Probe sample, Vec3 heading) {
        if (!SewvDiag.groundPathingVerbose()) return;
        long now = this.unit.level().getGameTime();
        if (now == this.lastSteerDiagTick) return;
        this.lastSteerDiagTick = now;
        SewvDiag.pathing(
                "steerTick unit={}#{} vehicle={}#{} slot={} offsetDeg={} hard={} stepDelta={} "
                        + "waterDepth={} blockY={} pitch={} centerWater={} desired={}",
                this.unit.getClass().getSimpleName(), this.unit.getId(),
                this.vehicle.getName().getString(), this.vehicle.getId(),
                slot, offset, sample.hard, sample.stepDelta, sample.waterDepth,
                this.vehicle.getBlockY(), this.vehicle.getXRot(),
                this.cachedCenterWater, desired);
    }

    private void logBlockedHeading(Vec3 dir, double distance, String reason) {
        if (!SewvDiag.groundPathingVerbose()) return;
        long now = this.unit.level().getGameTime();
        if (now == this.lastBlockedDiagTick) return;
        this.lastBlockedDiagTick = now;
        SewvDiag.pathing(
                "headingClear BLOCKED unit={}#{} vehicle={}#{} reason={} dir={} distance={} "
                        + "stepDelta={} waterDepth={} blockY={} maxUpStep={} amphibious={} inWater={}",
                this.unit.getClass().getSimpleName(), this.unit.getId(),
                this.vehicle.getName().getString(), this.vehicle.getId(),
                reason, dir, distance, this.lastStepDelta, this.lastWaterDepth,
                this.vehicle.getBlockY(), this.maxUpStep, this.amphibious, this.vehicle.isInWater());
    }

    @Override
    protected List<AABB> buildObstacles(double reach) {
        double half = halfWidth();
        // ORCA's own culling guidance (paper §5.1): a peer farther than (selfSpeed+peerSpeed)*tau
        // can never collide within the horizon, so it's safe to leave out of the search — but
        // that reach is a DIFFERENT quantity from the terrain-whisker lookahead this method is
        // normally called with, and coupling the two meant a genuine 20-30-tick collision course
        // starting a few blocks past LOOKAHEAD_DISTANCE was invisible until the peer was nearly
        // on top of the hull. Peer speed is approximated as self speed absent a reliable per-hull
        // max-speed source.
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
        this.allyFootObstacles = this.unit.level().getEntitiesOfClass(AbstractUnit.class, search,
                        this::isAllyFootObstacle).stream()
                .map(u -> u.getBoundingBox().inflate(half, 0.0, half))
                .toList();
        return List.of();
    }

    private boolean isAllyFootObstacle(AbstractUnit other) {
        if (other == this.unit || !other.isAlive()) return false;
        if (other.getVehicle() != null) return false;
        return VehicleTargeting.isSameFaction(this.unit, other);
    }

    private static boolean isBlockedByHull(List<AABB> obstacles, double x, double z) {
        for (AABB box : obstacles) {
            if (x >= box.minX && x <= box.maxX && z >= box.minZ && z <= box.maxZ) return true;
        }
        return false;
    }

    private static final class Probe {
        float hard;
        float skirt;
        String reason = "ok";
        double stepDelta;
        int waterDepth;
    }

    private static final class Column {
        double floorY = GroundMobility.NO_FLOOR;
        int waterDepth;
        boolean lava;
        /** Wall block is a fellable tree log — probeLine treats it as a soft hazard, not a hard
         * step block. See {@link #probeColumn}. */
        boolean tree;
    }

}
