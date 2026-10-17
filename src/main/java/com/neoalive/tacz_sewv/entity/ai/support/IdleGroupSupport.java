package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.command.Assignment;
import com.neoalive.tacz_sewv.entity.ai.command.CrewAssignment;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.sensor.HullLocalScan;
import com.neoalive.tacz_sewv.entity.ai.utility.Facts;

/**
 * Decentralized idle-group coordination for ground vehicles: polygon {@code IDLE_HOLD} and
 * constant-bearing {@code IDLE_TRAVEL} column.
 *
 * <p>No central manager — each crew builds the same membership list (friendly idle ground drivers
 * within radius, sorted by entity id), derives its slot/leader locally, and steers via the normal
 * pathfinder. State lives on the hull's persistent NBT so a crew change inherits it.
 */
public final class IdleGroupSupport {

    private IdleGroupSupport() {}

    public static final byte MODE_NONE = 0;
    public static final byte MODE_HOLD = 1;
    public static final byte MODE_TRAVEL = 2;

    private static final String MODE_KEY = "tacz_sewv:idle_mode";
    private static final String HOLD_UNTIL_KEY = "tacz_sewv:idle_hold_until";
    private static final String HOLD_STARTED_KEY = "tacz_sewv:idle_hold_started";
    private static final String SCRAMBLE_X_KEY = "tacz_sewv:idle_scramble_x";
    private static final String SCRAMBLE_Z_KEY = "tacz_sewv:idle_scramble_z";
    private static final String SLOT_LEADER_KEY = "tacz_sewv:idle_slot_leader";
    private static final String SLOT_INDEX_KEY = "tacz_sewv:idle_slot_index";
    private static final String BEARING_KEY = "tacz_sewv:idle_bearing";
    private static final String TRAVEL_LEADER_KEY = "tacz_sewv:idle_travel_leader";
    private static final String SPACING_KEY = "tacz_sewv:idle_spacing";
    private static final String STUCK_SINCE_KEY = "tacz_sewv:idle_travel_stuck_since";
    private static final String STUCK_X_KEY = "tacz_sewv:idle_stuck_x";
    private static final String STUCK_Z_KEY = "tacz_sewv:idle_stuck_z";
    private static final String HAS_BEARING_KEY = "tacz_sewv:idle_has_bearing";
    private static final String LAST_BEARING_KEY = "tacz_sewv:idle_last_bearing";
    private static final String GROUND_Y_X_KEY = "tacz_sewv:idle_ground_y_x";
    private static final String GROUND_Y_Z_KEY = "tacz_sewv:idle_ground_y_z";
    private static final String GROUND_Y_VAL_KEY = "tacz_sewv:idle_ground_y_val";
    private static final String CONTACT_PEER_KEY = "tacz_sewv:idle_contact_peer";
    private static final String CONTACT_PLAYER_KEY = "tacz_sewv:idle_contact_player";
    private static final String CONTACT_VEHICLE_KEY = "tacz_sewv:idle_contact_vehicle";

    private static final int MAX_HOLD_SIZE = 5;
    private static final int OPEN_GROUND_SAMPLES = 8;
    private static final double OPEN_GROUND_STEP = 16.0;

    private static final LongAdder IDLE_SCAN_CALLS = new LongAdder();
    private static final LongAdder IDLE_SNAPSHOT_CACHE_HITS = new LongAdder();
    private static final LongAdder GROUND_Y_PROBES = new LongAdder();
    private static final LongAdder GROUND_Y_CACHE_HITS = new LongAdder();
    private static final LongAdder TRAVEL_INVALIDATE_SCANS = new LongAdder();

    private static final ConcurrentHashMap<ClusterCacheKey, CachedCluster> CLUSTER_CACHE = new ConcurrentHashMap<>();
    private static long lastClusterCacheGen = Long.MIN_VALUE;

    private record ClusterCacheKey(int leaderUnitId, long refreshGen, ResourceKey<Level> dimension) {}

    private static final class CachedCluster {
        final List<Member> members;
        final double centerX;
        final double centerY;
        final double centerZ;
        final boolean peerVehicle;
        final boolean playerNearby;

        CachedCluster(List<Member> members, double centerX, double centerY, double centerZ,
                      boolean peerVehicle, boolean playerNearby) {
            this.members = List.copyOf(members);
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.peerVehicle = peerVehicle;
            this.playerNearby = playerNearby;
        }
    }

    /** Idle perf counters for {@code /sewv debug perf}. */
    public static String stats() {
        return "idleScanCalls=" + IDLE_SCAN_CALLS.sum()
                + " idleSnapshotCacheHits=" + IDLE_SNAPSHOT_CACHE_HITS.sum()
                + " groundYProbes=" + GROUND_Y_PROBES.sum()
                + " groundYCacheHits=" + GROUND_Y_CACHE_HITS.sum()
                + " travelInvalidateScans=" + TRAVEL_INVALIDATE_SCANS.sum();
    }

    public static void resetStats() {
        IDLE_SCAN_CALLS.reset();
        IDLE_SNAPSHOT_CACHE_HITS.reset();
        GROUND_Y_PROBES.reset();
        GROUND_Y_CACHE_HITS.reset();
        TRAVEL_INVALIDATE_SCANS.reset();
    }

    private static final int MAX_SLOT_RISE = 16;
    private static final double STUCK_MOVE_EPS = 1.5;

    /** One vehicle's view of its idle group for a Facts refresh window. */
    public static final class Snapshot {
        public final List<Member> members;
        public final int size;
        public final int index;
        public final int leaderId;
        public final boolean isLeader;
        public final double centerX;
        public final double centerZ;
        public final double centerY;
        public final boolean peerVehicleNearby;
        public final boolean playerNearby;

        Snapshot(List<Member> members, int index, double centerX, double centerY, double centerZ,
                 boolean peerVehicleNearby, boolean playerNearby) {
            this.members = members;
            this.size = members.size();
            this.index = index;
            this.leaderId = members.isEmpty() ? -1 : members.get(0).unitId;
            this.isLeader = !members.isEmpty() && members.get(index).unitId == this.leaderId;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.peerVehicleNearby = peerVehicleNearby;
            this.playerNearby = playerNearby;
        }
    }

    /** One eligible idle ground driver in the group. */
    public static final class Member {
        public final int unitId;
        public final int hullId;
        public final AbstractUnit unit;
        public final VehicleEntity hull;
        public final double x;
        public final double y;
        public final double z;

        Member(AbstractUnit unit, VehicleEntity hull) {
            this.unit = unit;
            this.hull = hull;
            this.unitId = unit.getId();
            this.hullId = hull.getId();
            this.x = hull.getX();
            this.y = hull.getY();
            this.z = hull.getZ();
        }
    }

    public static boolean hybridEnabled() {
        try {
            return SewvConfig.IDLE_HYBRID_ENABLED.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean isGroundIdleEligible(AbstractUnit unit, @Nullable VehicleEntity hull) {
        if (unit == null || hull == null || !unit.isAlive() || hull.isWreck()) return false;
        if (hull.getFirstPassenger() != unit) return false;
        if (unit.getTarget() != null) return false;
        if (VehicleTargeting.underStandingOrder(unit)) return false;
        EngineType type = HullFacts.engineType(hull);
        return type == EngineType.WHEEL || type == EngineType.TRACK;
    }

    /**
     * Build the idle group around {@code unit}/{@code hull}. Call once per Facts refresh.
     */
    public static Snapshot scan(AbstractUnit unit, VehicleEntity hull) {
        IDLE_SCAN_CALLS.increment();
        List<Member> members = new ArrayList<>();
        if (isGroundIdleEligible(unit, hull)) {
            members.add(new Member(unit, hull));
        } else {
            return emptySnapshot(hull);
        }

        double groupR = groupRadius();
        double groupRSq = groupR * groupR;
        Level level = hull.level();
        double scanR = targetScanRadius();
        double detectR = travelDetectRadius();
        double queryR = Math.max(groupR, Math.max(scanR, detectR));

        List<LivingEntity> living;
        if (scanR >= queryR) {
            living = HullLocalScan.livingInScanCylinder(hull);
        } else {
            AABB box = hull.getBoundingBox().inflate(queryR, Math.max(8.0, hull.getBbHeight() + 4.0), queryR);
            living = level.getEntitiesOfClass(LivingEntity.class, box, Entity::isAlive);
        }

        for (LivingEntity livingEntity : living) {
            if (!(livingEntity instanceof AbstractUnit other) || other == unit || !other.isAlive()) continue;
            if (!(other.getVehicle() instanceof VehicleEntity otherHull)) continue;
            if (otherHull.getFirstPassenger() != other) continue;
            if (!isGroundIdleEligible(other, otherHull)) continue;
            if (!VehicleTargeting.isFriendly(unit, other)) continue;
            double dx = otherHull.getX() - hull.getX();
            double dz = otherHull.getZ() - hull.getZ();
            if (dx * dx + dz * dz > groupRSq) continue;
            members.add(new Member(other, otherHull));
        }

        members.sort(Comparator.comparingInt(m -> m.unitId));

        long refreshGen = refreshGeneration(level);
        maybePruneClusterCache(refreshGen);
        int leaderUnitId = members.get(0).unitId;
        ClusterCacheKey cacheKey = new ClusterCacheKey(leaderUnitId, refreshGen, level.dimension());
        CachedCluster cached = CLUSTER_CACHE.get(cacheKey);

        double cx;
        double cy;
        double cz;
        boolean peerVehicle;
        boolean playerNearby;
        if (cached != null && sameMembership(cached.members, members)) {
            IDLE_SNAPSHOT_CACHE_HITS.increment();
            members = new ArrayList<>(cached.members);
            cx = cached.centerX;
            cy = cached.centerY;
            cz = cached.centerZ;
            peerVehicle = cached.peerVehicle;
            playerNearby = cached.playerNearby;
        } else {
            cx = 0.0;
            cy = 0.0;
            cz = 0.0;
            for (Member m : members) {
                cx += m.x;
                cy += m.y;
                cz += m.z;
            }
            cx /= members.size();
            cy /= members.size();
            cz /= members.size();
            Detect detect = detectContacts(unit, hull, members, living);
            peerVehicle = detect.peerVehicle;
            playerNearby = detect.player;
            CLUSTER_CACHE.put(cacheKey, new CachedCluster(members, cx, cy, cz, peerVehicle, playerNearby));
        }

        int index = 0;
        for (int i = 0; i < members.size(); i++) {
            if (members.get(i).unitId == unit.getId()) {
                index = i;
                break;
            }
        }

        CrewAssignment.Snapshot assign = CrewAssignment.of(unit.getId());
        if (assign != null && assign.hasDest()
                && (assign.role() == Assignment.Role.IDLE_HOLD
                || assign.role() == Assignment.Role.IDLE_TRAVEL)) {
            cx = assign.destX();
            cz = assign.destZ();
        }

        return new Snapshot(members, index, cx, cy, cz, peerVehicle, playerNearby);
    }

    private static boolean sameMembership(List<Member> a, List<Member> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).unitId != b.get(i).unitId) return false;
        }
        return true;
    }

    private static Snapshot emptySnapshot(VehicleEntity hull) {
        return new Snapshot(List.of(), 0, hull.getX(), hull.getY(), hull.getZ(), false, false);
    }

    private record Detect(boolean peerVehicle, boolean player) {}

    private static Detect detectContacts(AbstractUnit unit, VehicleEntity hull, List<Member> members,
                                         List<LivingEntity> living) {
        double detectR = travelDetectRadius();
        double detectRSq = detectR * detectR;
        Set<Integer> memberHullIds = new HashSet<>();
        Set<Integer> memberUnitIds = new HashSet<>();
        for (Member m : members) {
            memberHullIds.add(m.hullId);
            memberUnitIds.add(m.unitId);
        }

        boolean peer = false;
        boolean player = false;
        for (LivingEntity e : living) {
            if (!e.isAlive() || e == unit) continue;
            double dx = e.getX() - hull.getX();
            double dz = e.getZ() - hull.getZ();
            if (dx * dx + dz * dz > detectRSq) continue;

            if (e instanceof Player p) {
                if (p.isCreative() || p.isSpectator()) continue;
                if (VehicleTargeting.isNonHostile(unit, p)) continue;
                player = true;
                continue;
            }
            if (memberUnitIds.contains(e.getId())) continue;
            Entity ride = e.getVehicle();
            if (ride instanceof VehicleEntity vh) {
                if (memberHullIds.contains(vh.getId())) continue;
                peer = true;
            }
        }
        return new Detect(peer, player);
    }

    /** Pure polygon slot math — used by destinations and headless self-check. */
    public static Vec3 holdSlotOffset(int index, int size, double baseRadius, double radiusMin,
                                      double radiusMax, double scrambleX, double scrambleZ) {
        int n = Math.min(Math.max(size, 1), MAX_HOLD_SIZE);
        if (n == 1) {
            return new Vec3(scrambleX, 0.0, scrambleZ);
        }
        double angle = index * (Math.PI * 2.0 / n);
        double radius = Mth.clamp(baseRadius * (1.0 + 0.2 * (n - 1)), radiusMin, radiusMax);
        return new Vec3(Math.cos(angle) * radius + scrambleX, 0.0, Math.sin(angle) * radius + scrambleZ);
    }

    /** Column offset behind the lead along a unit bearing: index 0 at origin, i behind by i*spacing. */
    public static Vec3 travelColumnOffset(int index, double bearingRad, double spacing) {
        if (index <= 0) return Vec3.ZERO;
        // Bearing stored as atan2(dx, dz): direction (sin, cos) in XZ.
        double fx = Math.sin(bearingRad);
        double fz = Math.cos(bearingRad);
        return new Vec3(-fx * spacing * index, 0.0, -fz * spacing * index);
    }

    public static byte modeOf(VehicleEntity hull) {
        return hull.getPersistentData().getByte(MODE_KEY);
    }

    public static void clear(VehicleEntity hull) {
        CompoundTag data = hull.getPersistentData();
        data.remove(MODE_KEY);
        data.remove(HOLD_UNTIL_KEY);
        data.remove(HOLD_STARTED_KEY);
        data.remove(SCRAMBLE_X_KEY);
        data.remove(SCRAMBLE_Z_KEY);
        data.remove(SLOT_LEADER_KEY);
        data.remove(SLOT_INDEX_KEY);
        data.remove(BEARING_KEY);
        data.remove(TRAVEL_LEADER_KEY);
        data.remove(SPACING_KEY);
        data.remove(STUCK_SINCE_KEY);
        data.remove(STUCK_X_KEY);
        data.remove(STUCK_Z_KEY);
        data.remove(HAS_BEARING_KEY);
        data.remove(LAST_BEARING_KEY);
        data.remove(GROUND_Y_X_KEY);
        data.remove(GROUND_Y_Z_KEY);
        data.remove(GROUND_Y_VAL_KEY);
        data.remove(CONTACT_PEER_KEY);
        data.remove(CONTACT_PLAYER_KEY);
        data.remove(CONTACT_VEHICLE_KEY);
        data.remove(DEBUG_DRIVE_KEY);
    }

    public static void onContact(AbstractUnit unit, VehicleEntity hull) {
        if (unit.getTarget() != null || VehicleTargeting.underStandingOrder(unit)) {
            clear(hull);
        }
    }

    public static void enterHold(AbstractUnit unit, VehicleEntity hull, Snapshot snap) {
        CompoundTag data = hull.getPersistentData();
        long now = hull.level().getGameTime();
        boolean fresh = data.getByte(MODE_KEY) != MODE_HOLD
                || !data.contains(HOLD_UNTIL_KEY)
                || data.getLong(HOLD_UNTIL_KEY) <= now;
        data.putByte(MODE_KEY, MODE_HOLD);
        data.remove(BEARING_KEY);
        data.remove(HAS_BEARING_KEY);
        data.remove(TRAVEL_LEADER_KEY);
        data.remove(STUCK_SINCE_KEY);

        if (fresh) {
            if (!snap.isLeader && snap.size > 1) {
                Member leader = snap.members.get(0);
                CompoundTag leaderData = leader.hull.isAlive() ? leader.hull.getPersistentData() : null;
                if (leaderData != null
                        && leaderData.contains(HOLD_UNTIL_KEY)
                        && leaderData.contains(HOLD_STARTED_KEY)) {
                    data.putLong(HOLD_UNTIL_KEY, leaderData.getLong(HOLD_UNTIL_KEY));
                    data.putLong(HOLD_STARTED_KEY, leaderData.getLong(HOLD_STARTED_KEY));
                } else {
                    rollHoldTimer(unit, hull, data, now);
                }
            } else {
                rollHoldTimer(unit, hull, data, now);
            }
        }

        ensureScramble(unit, hull, snap);
    }

    private static void rollHoldTimer(AbstractUnit unit, VehicleEntity hull, CompoundTag data, long now) {
        int min = holdMinTicks();
        int max = Math.max(min, holdMaxTicks());
        int span = Math.max(1, max - min);
        long until = now + min + unit.getRandom().nextInt(span);
        data.putLong(HOLD_UNTIL_KEY, until);
        data.putLong(HOLD_STARTED_KEY, now);
    }

    public static void enterTravel(AbstractUnit unit, VehicleEntity hull, Snapshot snap) {
        CompoundTag data = hull.getPersistentData();
        long now = hull.level().getGameTime();
        data.putByte(MODE_KEY, MODE_TRAVEL);
        data.putInt(TRAVEL_LEADER_KEY, snap.leaderId);
        data.remove(STUCK_SINCE_KEY);
        data.putDouble(STUCK_X_KEY, hull.getX());
        data.putDouble(STUCK_Z_KEY, hull.getZ());
        data.putLong(STUCK_SINCE_KEY, now);

        if (!data.contains(SPACING_KEY)) {
            double sMin = spacingMin();
            double sMax = Math.max(sMin, spacingMax());
            double spacing = sMin + unit.getRandom().nextDouble() * (sMax - sMin);
            data.putDouble(SPACING_KEY, spacing);
        }

        if (snap.isLeader) {
            ensureLeaderBearing(unit, hull, snap);
        }
    }

    private static void ensureScramble(AbstractUnit unit, VehicleEntity hull, Snapshot snap) {
        CompoundTag data = hull.getPersistentData();
        int leader = snap.leaderId;
        int index = snap.index;
        if (data.contains(SCRAMBLE_X_KEY)
                && data.getInt(SLOT_LEADER_KEY) == leader
                && data.getInt(SLOT_INDEX_KEY) == index) {
            return;
        }
        double r = scrambleRadius();
        RandomSource rng = unit.getRandom();
        data.putDouble(SCRAMBLE_X_KEY, (rng.nextDouble() * 2.0 - 1.0) * r);
        data.putDouble(SCRAMBLE_Z_KEY, (rng.nextDouble() * 2.0 - 1.0) * r);
        data.putInt(SLOT_LEADER_KEY, leader);
        data.putInt(SLOT_INDEX_KEY, index);
    }

    private static void ensureLeaderBearing(AbstractUnit unit, VehicleEntity hull, Snapshot snap) {
        CompoundTag data = hull.getPersistentData();
        if (data.getBoolean(HAS_BEARING_KEY)) return;

        float bearing = pickTravelBearing(unit, hull, snap);
        data.putFloat(BEARING_KEY, bearing);
        data.putFloat(LAST_BEARING_KEY, bearing);
        data.putBoolean(HAS_BEARING_KEY, true);
    }

    private static float pickTravelBearing(AbstractUnit unit, VehicleEntity hull, Snapshot snap) {
        CompoundTag data = hull.getPersistentData();
        if (data.contains(LAST_BEARING_KEY)) {
            return data.getFloat(LAST_BEARING_KEY);
        }

        CrewAssignment.Snapshot assign = CrewAssignment.of(unit.getId());
        if (assign != null && assign.hasDest()
                && assign.role() == Assignment.Role.IDLE_TRAVEL) {
            double dx = assign.destX() - hull.getX();
            double dz = assign.destZ() - hull.getZ();
            if (dx * dx + dz * dz > 1.0E-4) {
                return (float) Math.atan2(dx, dz);
            }
        }

        float away = bearingAwayFromNearestPlayer(unit, hull);
        if (!Float.isNaN(away)) {
            return away;
        }

        float open = openGroundBearing(hull);
        if (!Float.isNaN(open)) {
            return open;
        }

        return unit.getRandom().nextFloat() * ((float) Math.PI * 2.0F);
    }

    /** Prefer marching away from the nearest non-friendly player in detect range. */
    private static float bearingAwayFromNearestPlayer(AbstractUnit unit, VehicleEntity hull) {
        double detectR = travelDetectRadius();
        double detectRSq = detectR * detectR;
        Player closest = null;
        double best = detectRSq;
        for (Player p : hull.level().getEntitiesOfClass(Player.class, hull.getBoundingBox().inflate(detectR))) {
            if (!p.isAlive() || p.isCreative() || p.isSpectator()) continue;
            if (VehicleTargeting.isNonHostile(unit, p)) continue;
            double dx = p.getX() - hull.getX();
            double dz = p.getZ() - hull.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 < best) {
                best = d2;
                closest = p;
            }
        }
        if (closest == null) return Float.NaN;
        return (float) Math.atan2(hull.getX() - closest.getX(), hull.getZ() - closest.getZ());
    }

    /** Sample eight headings and pick the one with the longest gentle slope probe. */
    private static float openGroundBearing(VehicleEntity hull) {
        Level level = hull.level();
        double bestScore = -1.0;
        float bestBearing = Float.NaN;
        int startY = Mth.floor(hull.getY());
        for (int i = 0; i < OPEN_GROUND_SAMPLES; i++) {
            float bearing = (float) (i * (Math.PI * 2.0 / OPEN_GROUND_SAMPLES));
            double fx = Math.sin(bearing);
            double fz = Math.cos(bearing);
            double score = 0.0;
            int lastY = startY;
            for (int step = 1; step <= 4; step++) {
                double px = hull.getX() + fx * OPEN_GROUND_STEP * step;
                double pz = hull.getZ() + fz * OPEN_GROUND_STEP * step;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(px), Mth.floor(pz));
                if (Math.abs(y - lastY) > 2) break;
                lastY = y;
                score += 1.0;
            }
            if (score > bestScore) {
                bestScore = score;
                bestBearing = bearing;
            }
        }
        return bestScore > 0.0 ? bestBearing : Float.NaN;
    }

    /** Fill Facts idle-group fields from a snapshot + hull NBT. Also runs invalidation/stuck. */
    public static void applyToFacts(AbstractUnit unit, VehicleEntity hull, Snapshot snap, Facts facts) {
        facts.idleGroupSize = snap.size;
        facts.idleGroupIndex = snap.index;
        facts.idleLeaderId = snap.leaderId;
        facts.idleIsLeader = snap.isLeader;
        facts.idleGroupOversize = snap.size > MAX_HOLD_SIZE;
        facts.idlePeerVehicleNearby = snap.peerVehicleNearby;
        facts.idlePlayerNearby = snap.playerNearby;
        facts.idleSnapshot = snap;

        CompoundTag data = hull.getPersistentData();
        byte mode = data.getByte(MODE_KEY);
        facts.idleTravelActive = mode == MODE_TRAVEL;

        long now = hull.level().getGameTime();
        if (mode == MODE_HOLD && data.contains(HOLD_UNTIL_KEY) && data.contains(HOLD_STARTED_KEY)) {
            long started = data.getLong(HOLD_STARTED_KEY);
            long until = data.getLong(HOLD_UNTIL_KEY);
            long span = Math.max(1L, until - started);
            facts.idleHoldElapsed = Mth.clamp((double) (now - started) / (double) span, 0.0, 1.0);
            facts.idleHoldExpired = now >= until;
        } else if (mode == MODE_NONE && snap.size > 0) {
            // No mode yet: treat as not expired so HOLD can win first.
            facts.idleHoldElapsed = 0.0;
            facts.idleHoldExpired = false;
        } else if (mode == MODE_TRAVEL) {
            facts.idleHoldElapsed = 1.0;
            facts.idleHoldExpired = true;
        } else {
            facts.idleHoldElapsed = 0.0;
            facts.idleHoldExpired = data.contains(HOLD_UNTIL_KEY) && now >= data.getLong(HOLD_UNTIL_KEY);
        }

        // Contact / orders wipe idle state.
        if (unit.getTarget() != null || VehicleTargeting.underStandingOrder(unit)) {
            if (!CrewAssignment.isIdleTasked(unit.getId())) {
                clear(hull);
                facts.idleTravelActive = false;
                facts.idleHoldExpired = false;
                facts.idleHoldElapsed = 0.0;
            }
        }

        if (mode == MODE_TRAVEL && !snap.members.isEmpty()) {
            if (!isDebugDrive(hull)) {
                if (snap.isLeader) {
                    boolean foreignVehicle = scanForeignVehicles(hull, snap);
                    writeContactFlags(hull, snap.peerVehicleNearby, snap.playerNearby, foreignVehicle);
                    if (shouldInvalidateFromContactFlags(hull)) {
                        invalidateTravel(hull, snap.size <= MAX_HOLD_SIZE);
                        facts.idleTravelActive = false;
                        facts.idleHoldExpired = false;
                        facts.idleHoldElapsed = 0.0;
                    } else if (isTravelStuck(hull, now)) {
                        invalidateTravel(hull, true);
                        facts.idleTravelActive = false;
                        facts.idleHoldExpired = false;
                        facts.idleHoldElapsed = 0.0;
                    }
                } else {
                    VehicleEntity leaderHull = snap.members.get(0).hull;
                    if (!leaderHull.isAlive()) {
                        invalidateTravel(hull, snap.size <= MAX_HOLD_SIZE);
                        facts.idleTravelActive = false;
                        facts.idleHoldExpired = false;
                        facts.idleHoldElapsed = 0.0;
                    } else {
                    CompoundTag leaderData = leaderHull.getPersistentData();
                    boolean invalidate;
                    if (leaderData.contains(CONTACT_PEER_KEY)) {
                        invalidate = shouldInvalidateFromContactFlags(leaderHull);
                    } else {
                        invalidate = snap.peerVehicleNearby || snap.playerNearby;
                    }
                    if (invalidate) {
                        invalidateTravel(hull, snap.size <= MAX_HOLD_SIZE);
                        facts.idleTravelActive = false;
                        facts.idleHoldExpired = false;
                        facts.idleHoldElapsed = 0.0;
                    } else if (isTravelStuck(hull, now)) {
                        invalidateTravel(hull, true);
                        facts.idleTravelActive = false;
                        facts.idleHoldExpired = false;
                        facts.idleHoldElapsed = 0.0;
                    }
                    }
                }
            }
        }
    }

    private static void writeContactFlags(VehicleEntity hull, boolean peer, boolean player, boolean foreignVehicle) {
        CompoundTag data = hull.getPersistentData();
        data.putBoolean(CONTACT_PEER_KEY, peer);
        data.putBoolean(CONTACT_PLAYER_KEY, player);
        data.putBoolean(CONTACT_VEHICLE_KEY, foreignVehicle);
    }

    private static boolean shouldInvalidateFromContactFlags(VehicleEntity hull) {
        CompoundTag data = hull.getPersistentData();
        return data.getBoolean(CONTACT_PEER_KEY)
                || data.getBoolean(CONTACT_PLAYER_KEY)
                || data.getBoolean(CONTACT_VEHICLE_KEY);
    }

    private static boolean scanForeignVehicles(VehicleEntity hull, Snapshot snap) {
        TRAVEL_INVALIDATE_SCANS.increment();
        double detectR = travelDetectRadius();
        Set<Integer> memberHullIds = new HashSet<>();
        for (Member m : snap.members) memberHullIds.add(m.hullId);
        AABB box = hull.getBoundingBox().inflate(detectR);
        for (VehicleEntity other : hull.level().getEntitiesOfClass(VehicleEntity.class, box, v -> !v.isWreck())) {
            if (other == hull) continue;
            if (memberHullIds.contains(other.getId())) continue;
            double dx = other.getX() - hull.getX();
            double dz = other.getZ() - hull.getZ();
            if (dx * dx + dz * dz <= detectR * detectR) return true;
        }
        return false;
    }

    private static boolean isTravelStuck(VehicleEntity hull, long now) {
        CompoundTag data = hull.getPersistentData();
        if (!data.contains(STUCK_SINCE_KEY)) {
            data.putLong(STUCK_SINCE_KEY, now);
            data.putDouble(STUCK_X_KEY, hull.getX());
            data.putDouble(STUCK_Z_KEY, hull.getZ());
            return false;
        }
        double lx = data.getDouble(STUCK_X_KEY);
        double lz = data.getDouble(STUCK_Z_KEY);
        double dx = hull.getX() - lx;
        double dz = hull.getZ() - lz;
        if (dx * dx + dz * dz > STUCK_MOVE_EPS * STUCK_MOVE_EPS) {
            data.putLong(STUCK_SINCE_KEY, now);
            data.putDouble(STUCK_X_KEY, hull.getX());
            data.putDouble(STUCK_Z_KEY, hull.getZ());
            return false;
        }
        return now - data.getLong(STUCK_SINCE_KEY) >= stuckTicks();
    }

    private static void invalidateTravel(VehicleEntity hull, boolean reenterHoldTimer) {
        CompoundTag data = hull.getPersistentData();
        if (data.getBoolean(HAS_BEARING_KEY)) {
            data.putFloat(LAST_BEARING_KEY, data.getFloat(BEARING_KEY));
        }
        data.putByte(MODE_KEY, MODE_NONE);
        data.remove(BEARING_KEY);
        data.remove(HAS_BEARING_KEY);
        data.remove(TRAVEL_LEADER_KEY);
        data.remove(STUCK_SINCE_KEY);
        data.remove(SPACING_KEY);
        data.remove(CONTACT_PEER_KEY);
        data.remove(CONTACT_PLAYER_KEY);
        data.remove(CONTACT_VEHICLE_KEY);
        if (reenterHoldTimer) {
            data.remove(HOLD_UNTIL_KEY);
            data.remove(HOLD_STARTED_KEY);
        }
    }

    @Nullable
    public static BlockPos holdDestination(AbstractUnit unit, VehicleEntity hull, @Nullable Facts facts) {
        return holdDestination(unit, hull, facts, null);
    }

    @Nullable
    static BlockPos holdDestination(AbstractUnit unit, VehicleEntity hull, @Nullable Facts facts,
                                    @Nullable Snapshot preferred) {
        Snapshot snap = resolveSnapshot(unit, hull, facts, preferred);
        if (snap.size == 0) return null;
        if (modeOf(hull) != MODE_HOLD) {
            enterHold(unit, hull, snap);
        } else {
            ensureScramble(unit, hull, snap);
        }

        CompoundTag data = hull.getPersistentData();
        double sx = data.getDouble(SCRAMBLE_X_KEY);
        double sz = data.getDouble(SCRAMBLE_Z_KEY);
        Vec3 off = holdSlotOffset(snap.index, snap.size, formationBaseRadius(),
                formationRadiusMin(), formationRadiusMax(), sx, sz);
        double x = snap.centerX + off.x;
        double z = snap.centerZ + off.z;
        double y = cachedGroundY(hull, x, z, snap.centerY);
        return BlockPos.containing(x, y, z);
    }

    /** Spatial column index: foremost hull along bearing is index 0. */
    static int travelColumnIndex(Snapshot snap, float bearingRad, int unitId) {
        if (snap.size <= 1) return 0;
        List<Member> sorted = new ArrayList<>();
        for (Member m : snap.members) {
            if (m.hull.isAlive()) sorted.add(m);
        }
        if (sorted.isEmpty()) return snap.index;
        double fx = Math.sin(bearingRad);
        double fz = Math.cos(bearingRad);
        sorted.sort(Comparator.comparingDouble(m -> -(m.hull.getX() * fx + m.hull.getZ() * fz)));
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).unitId == unitId) return i;
        }
        return snap.index;
    }

    @Nullable
    public static BlockPos travelDestination(AbstractUnit unit, VehicleEntity hull, @Nullable Facts facts) {
        return travelDestination(unit, hull, facts, null);
    }

    @Nullable
    static BlockPos travelDestination(AbstractUnit unit, VehicleEntity hull, @Nullable Facts facts,
                                      @Nullable Snapshot preferred) {
        Snapshot snap = resolveSnapshot(unit, hull, facts, preferred);
        if (snap.size == 0) {
            // Solo travel after hold timeout still valid — treat self as group of 1.
            if (!isGroundIdleEligible(unit, hull)) return null;
            snap = new Snapshot(List.of(new Member(unit, hull)), 0,
                    hull.getX(), hull.getY(), hull.getZ(), false, false);
            if (facts != null) facts.idleSnapshot = snap;
        }
        if (modeOf(hull) != MODE_TRAVEL) {
            enterTravel(unit, hull, snap);
        }

        float bearing = resolveBearing(unit, hull, snap);
        double spacing = hull.getPersistentData().contains(SPACING_KEY)
                ? hull.getPersistentData().getDouble(SPACING_KEY)
                : spacingMin();
        int colIndex = travelColumnIndex(snap, bearing, unit.getId());
        Member leader = liveLeader(snap);
        if (leader == null) return null;

        if (colIndex == 0) {
            double lead = leadDistance();
            double fx = Math.sin(bearing);
            double fz = Math.cos(bearing);
            VehicleEntity leadHull = leader.hull;
            double x = leadHull.getX() + fx * lead;
            double z = leadHull.getZ() + fz * lead;
            double y = cachedGroundY(hull, x, z, leadHull.getY());
            return BlockPos.containing(x, y, z);
        }

        Vec3 off = travelColumnOffset(colIndex, bearing, spacing);
        double x = leader.hull.getX() + off.x;
        double z = leader.hull.getZ() + off.z;
        double y = cachedGroundY(hull, x, z, leader.hull.getY());
        return BlockPos.containing(x, y, z);
    }

    private static Snapshot resolveSnapshot(AbstractUnit unit, VehicleEntity hull, @Nullable Facts facts,
                                            @Nullable Snapshot preferred) {
        if (preferred != null && preferred.size > 0) return preferred;
        Snapshot snap = facts != null ? facts.idleSnapshot : null;
        if (snap == null || snap.size == 0) {
            snap = scan(unit, hull);
            if (facts != null) facts.idleSnapshot = snap;
        }
        return snap;
    }

    private static Snapshot resolveSnapshot(AbstractUnit unit, VehicleEntity hull, @Nullable Facts facts) {
        return resolveSnapshot(unit, hull, facts, null);
    }

    /** Unit-id leader if its hull is still alive; otherwise the first live member. */
    @Nullable
    private static Member liveLeader(Snapshot snap) {
        if (snap.members.isEmpty()) return null;
        Member nominal = snap.members.get(0);
        if (nominal.hull.isAlive()) return nominal;
        for (Member m : snap.members) {
            if (m.hull.isAlive()) return m;
        }
        return null;
    }

    private static float resolveBearing(AbstractUnit unit, VehicleEntity hull, Snapshot snap) {
        if (snap.isLeader) {
            ensureLeaderBearing(unit, hull, snap);
            return hull.getPersistentData().getFloat(BEARING_KEY);
        }
        // Followers read the leader hull's bearing.
        Member leader = liveLeader(snap);
        if (leader == null) {
            return (float) Math.toRadians(hull.getYRot());
        }
        CompoundTag leaderData = leader.hull.getPersistentData();
        if (leaderData.getBoolean(HAS_BEARING_KEY)) {
            return leaderData.getFloat(BEARING_KEY);
        }
        // Leader has not rolled yet — nudge them by reading; if still missing, face leader heading.
        if (leader.unit != null) {
            ensureLeaderBearing(leader.unit, leader.hull, snap);
            if (leaderData.getBoolean(HAS_BEARING_KEY)) {
                return leaderData.getFloat(BEARING_KEY);
            }
        }
        return (float) Math.atan2(leader.hull.getX() - hull.getX(), leader.hull.getZ() - hull.getZ());
    }

    static double groundY(Level level, double x, double z, double anchorY) {
        GROUND_Y_PROBES.increment();
        int probed = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
        return Math.abs(probed - anchorY) > MAX_SLOT_RISE ? anchorY : probed;
    }

    /** Heightmap probe cached on the hull until destination XZ cell changes. */
    private static double cachedGroundY(VehicleEntity hull, double x, double z, double anchorY) {
        int cellX = Mth.floor(x);
        int cellZ = Mth.floor(z);
        CompoundTag data = hull.getPersistentData();
        if (data.contains(GROUND_Y_X_KEY)
                && data.getInt(GROUND_Y_X_KEY) == cellX
                && data.getInt(GROUND_Y_Z_KEY) == cellZ) {
            GROUND_Y_CACHE_HITS.increment();
            return data.getDouble(GROUND_Y_VAL_KEY);
        }
        double y = groundY(hull.level(), x, z, anchorY);
        data.putInt(GROUND_Y_X_KEY, cellX);
        data.putInt(GROUND_Y_Z_KEY, cellZ);
        data.putDouble(GROUND_Y_VAL_KEY, y);
        return y;
    }

    private static long refreshGeneration(Level level) {
        long now = level.getGameTime();
        int interval;
        try {
            interval = SewvConfig.UTILITY_REFRESH_INTERVAL_TICKS.get();
        } catch (Throwable ignored) {
            interval = 30;
        }
        return now / Math.max(1, interval);
    }

    private static void maybePruneClusterCache(long refreshGen) {
        if (refreshGen == lastClusterCacheGen) return;
        CLUSTER_CACHE.entrySet().removeIf(e -> e.getKey().refreshGen() != refreshGen);
        lastClusterCacheGen = refreshGen;
    }

    private static double targetScanRadius() {
        try { return SewvConfig.VEHICLE_TARGET_SCAN_RADIUS.get(); } catch (Throwable t) { return 96.0; }
    }

    private static double groupRadius() {
        try { return SewvConfig.IDLE_GROUP_RADIUS.get(); } catch (Throwable t) { return 50.0; }
    }

    private static double formationBaseRadius() {
        try { return SewvConfig.IDLE_FORMATION_BASE_RADIUS.get(); } catch (Throwable t) { return 15.0; }
    }

    private static double formationRadiusMin() {
        try { return SewvConfig.IDLE_FORMATION_RADIUS_MIN.get(); } catch (Throwable t) { return 15.0; }
    }

    private static double formationRadiusMax() {
        try { return SewvConfig.IDLE_FORMATION_RADIUS_MAX.get(); } catch (Throwable t) { return 40.0; }
    }

    private static double scrambleRadius() {
        try { return SewvConfig.IDLE_SCRAMBLE_RADIUS.get(); } catch (Throwable t) { return 5.0; }
    }

    private static int holdMinTicks() {
        try { return SewvConfig.IDLE_HOLD_MIN_TICKS.get(); } catch (Throwable t) { return 100; }
    }

    private static int holdMaxTicks() {
        try { return SewvConfig.IDLE_HOLD_MAX_TICKS.get(); } catch (Throwable t) { return 200; }
    }

    private static double leadDistance() {
        try { return SewvConfig.IDLE_TRAVEL_LEAD_DISTANCE.get(); } catch (Throwable t) { return 500.0; }
    }

    private static double spacingMin() {
        try { return SewvConfig.IDLE_TRAVEL_SPACING_MIN.get(); } catch (Throwable t) { return 5.0; }
    }

    private static double spacingMax() {
        try { return SewvConfig.IDLE_TRAVEL_SPACING_MAX.get(); } catch (Throwable t) { return 8.0; }
    }

    private static double travelDetectRadius() {
        try { return SewvConfig.IDLE_TRAVEL_DETECT_RADIUS.get(); } catch (Throwable t) { return 20.0; }
    }

    private static int stuckTicks() {
        try { return SewvConfig.IDLE_TRAVEL_STUCK_TICKS.get(); } catch (Throwable t) { return 600; }
    }

    // ---- debug (/sewv debug idle*) ----

    private static final String DEBUG_DRIVE_KEY = "tacz_sewv:idle_debug_drive";

    /** When set, {@link DriveVehicleGoal} steers from hull NBT mode even before the scorer catches up. */
    public static void setDebugDrive(VehicleEntity hull, boolean on) {
        if (on) hull.getPersistentData().putBoolean(DEBUG_DRIVE_KEY, true);
        else hull.getPersistentData().remove(DEBUG_DRIVE_KEY);
    }

    public static boolean isDebugDrive(VehicleEntity hull) {
        return hull.getPersistentData().getBoolean(DEBUG_DRIVE_KEY);
    }

    /** Force the hold timer to elapsed so travel can win on the next Facts refresh. */
    public static void debugExpireHold(VehicleEntity hull) {
        long now = hull.level().getGameTime();
        CompoundTag data = hull.getPersistentData();
        data.putByte(MODE_KEY, MODE_HOLD);
        data.putLong(HOLD_STARTED_KEY, now - 1);
        data.putLong(HOLD_UNTIL_KEY, now);
    }

    /** Override leader bearing (radians) before {@link #enterTravel}. */
    public static void debugSetBearing(VehicleEntity hull, float bearingRad) {
        CompoundTag data = hull.getPersistentData();
        data.putFloat(BEARING_KEY, bearingRad);
        data.putBoolean(HAS_BEARING_KEY, true);
    }

    public static String modeLabel(byte mode) {
        return switch (mode) {
            case MODE_HOLD -> "hold";
            case MODE_TRAVEL -> "travel";
            default -> "none";
        };
    }

    /** One-line snapshot for {@code /sewv debug idleStatus}. */
    public static String describeStatus(AbstractUnit unit, VehicleEntity hull, Snapshot snap) {
        CompoundTag data = hull.getPersistentData();
        long now = hull.level().getGameTime();
        String holdLeft = "";
        if (data.contains(HOLD_UNTIL_KEY)) {
            long left = data.getLong(HOLD_UNTIL_KEY) - now;
            holdLeft = left > 0 ? " holdIn=" + left + "t" : " holdExpired";
        }
        BlockPos slot = holdDestination(unit, hull, null, snap);
        BlockPos march = travelDestination(unit, hull, null, snap);
        String slotStr = slot != null ? slot.toShortString() : "-";
        String marchStr = march != null ? march.toShortString() : "-";
        float bearing = data.getBoolean(HAS_BEARING_KEY) ? data.getFloat(BEARING_KEY) : Float.NaN;
        String bearingStr = Float.isNaN(bearing) ? "-" : String.format("%.0f°", Math.toDegrees(bearing));
        return String.format(
                "unit#%d hull#%d mode=%s debugDrive=%s group=%d idx=%d leader=%d slot=%s march=%s bearing=%s%s",
                unit.getId(), hull.getId(), modeLabel(modeOf(hull)), isDebugDrive(hull),
                snap.size, snap.index, snap.leaderId, slotStr, marchStr, bearingStr, holdLeft);
    }
}
