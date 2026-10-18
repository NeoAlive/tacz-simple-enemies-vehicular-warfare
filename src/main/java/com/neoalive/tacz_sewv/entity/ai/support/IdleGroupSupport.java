package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.command.Assignment;
import com.neoalive.tacz_sewv.entity.ai.command.CrewAssignment;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.formation.FormationFrame;
import com.neoalive.tacz_sewv.entity.ai.formation.FormationGeometry;
import com.neoalive.tacz_sewv.entity.ai.formation.FormationSpec;
import com.neoalive.tacz_sewv.entity.ai.formation.GroundSnap;
import com.neoalive.tacz_sewv.entity.ai.march.March;
import com.neoalive.tacz_sewv.entity.ai.march.MarchRegistry;
import com.neoalive.tacz_sewv.entity.ai.sensor.HullLocalScan;
import com.neoalive.tacz_sewv.entity.ai.utility.Facts;

/**
 * Idle-group coordination for ground vehicles: the polygon {@code IDLE_HOLD}. Travel is a march
 * ({@code entity/ai/march}): the coordinator forms it for the whole cluster at once, and the hold here is what a
 * group does until then.
 *
 * <p><b>Membership has one source.</b> {@code CommandCoordinator} clusters idle drivers each pass and
 * hands each cluster here ({@link #publishCluster}) with every member's hold slot already matched
 * ({@code SlotMatcher}). {@link #scan} reads that membership when there is one and only falls back to its
 * own local view (friendly idle drivers within radius of THIS crew) when the coordinator is off. Two crews
 * deriving membership locally can disagree — each sees a different radius — and then both number
 * themselves into the same slot; that was the reason for the split. The slot geometry itself is the
 * shared formation kernel ({@code RING}). State lives on the hull's persistent NBT so a crew change inherits
 * it; the travel keys still listed below are only ever removed, so an old save cleans itself up.
 */
public final class IdleGroupSupport {

    private IdleGroupSupport() {}

    public static final byte MODE_NONE = 0;
    public static final byte MODE_HOLD = 1;

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

    /** Largest group that holds a polygon; a bigger one marches. The coordinator reads the same number. */
    public static final int MAX_HOLD_SIZE = 5;

    private static final LongAdder IDLE_SCAN_CALLS = new LongAdder();
    private static final LongAdder IDLE_SNAPSHOT_CACHE_HITS = new LongAdder();
    private static final LongAdder GROUND_Y_PROBES = new LongAdder();
    private static final LongAdder GROUND_Y_CACHE_HITS = new LongAdder();

    private static final ConcurrentHashMap<ClusterCacheKey, CachedCluster> CLUSTER_CACHE = new ConcurrentHashMap<>();

    /**
     * The coordinator's clusters, by member unit id. Replaced wholesale every command pass ({@link #beginPublish}
     * moves the live set to {@link #PREVIOUS_PUBLISHED}, where the next pass reads last pass's slots for
     * stickiness). Both run on the server thread inside one tick event, so no AI tick ever sees it half-built.
     */
    private static volatile Map<Integer, Published> PUBLISHED = new HashMap<>();
    private static volatile Map<Integer, Published> PREVIOUS_PUBLISHED = new HashMap<>();

    /** One unit's cluster as the coordinator dealt it: sorted member ids, and its hold slot (-1 when marching). */
    private record Published(int[] memberIds, int slot) {}
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

    /** Start a command pass: last pass's clusters become the stickiness reference, the live set empties. */
    public static void beginPublish() {
        PREVIOUS_PUBLISHED = PUBLISHED;
        PUBLISHED = new HashMap<>();
    }

    /** Forget everything — server start/stop, or the command system switched off. */
    public static void clearPublished() {
        PUBLISHED = new HashMap<>();
        PREVIOUS_PUBLISHED = new HashMap<>();
    }

    /**
     * Record one coordinator cluster. {@code memberIds} sorted ascending; {@code slots[i]} is member i's hold
     * slot, or -1 for every member of a marching cluster.
     */
    public static void publishCluster(int[] memberIds, int[] slots) {
        int[] ids = memberIds.clone();
        for (int i = 0; i < ids.length; i++) {
            PUBLISHED.put(ids[i], new Published(ids, slots[i]));
        }
    }

    /** The hold slot {@code unitId} held last pass, or -1. */
    public static int previousSlot(int unitId) {
        Published p = PREVIOUS_PUBLISHED.get(unitId);
        return p == null ? -1 : p.slot();
    }

    /** Hold slot centres for an {@code n}-member cluster around {@code (cx, cz)}, scramble not applied. */
    public static Vec3[] holdRing(double cx, double cz, int n) {
        Vec3[] out = new Vec3[n];
        for (int i = 0; i < n; i++) {
            Vec3 off = holdSlotOffset(i, n, formationBaseRadius(), formationRadiusMin(), formationRadiusMax(), 0.0, 0.0);
            out[i] = new Vec3(cx + off.x, 0.0, cz + off.z);
        }
        return out;
    }

    /** Ring radius for an {@code n}-member hold — the stickiness scale the coordinator matches with. */
    public static double holdRadius(int n) {
        int k = Math.min(Math.max(n, 1), MAX_HOLD_SIZE);
        return Mth.clamp(formationBaseRadius() * (1.0 + 0.2 * (k - 1)), formationRadiusMin(), formationRadiusMax());
    }

    /** Idle perf counters for {@code /sewv debug perf}. */
    public static String stats() {
        return "idleScanCalls=" + IDLE_SCAN_CALLS.sum()
                + " idleSnapshotCacheHits=" + IDLE_SNAPSHOT_CACHE_HITS.sum()
                + " groundYProbes=" + GROUND_Y_PROBES.sum()
                + " groundYCacheHits=" + GROUND_Y_CACHE_HITS.sum();
    }

    public static void resetStats() {
        IDLE_SCAN_CALLS.reset();
        IDLE_SNAPSHOT_CACHE_HITS.reset();
        GROUND_Y_PROBES.reset();
        GROUND_Y_CACHE_HITS.reset();
    }

    private static final double STUCK_MOVE_EPS = 1.5;

    /** One vehicle's view of its idle group for a Facts refresh window. */
    public static final class Snapshot {
        public final List<Member> members;
        public final int size;
        public final int index;
        /**
         * Hold slot: the coordinator's matched slot when this snapshot came from a published cluster, else
         * {@link #index}. Distinct from {@code index}, which is the position in {@link #members} and is what
         * leadership reads.
         */
        public final int slot;
        public final int leaderId;
        public final boolean isLeader;
        public final double centerX;
        public final double centerZ;
        public final double centerY;
        public final boolean peerVehicleNearby;
        public final boolean playerNearby;

        Snapshot(List<Member> members, int index, double centerX, double centerY, double centerZ,
                 boolean peerVehicleNearby, boolean playerNearby) {
            this(members, index, index, centerX, centerY, centerZ, peerVehicleNearby, playerNearby);
        }

        Snapshot(List<Member> members, int index, int slot, double centerX, double centerY, double centerZ,
                 boolean peerVehicleNearby, boolean playerNearby) {
            this.members = members;
            this.size = members.size();
            this.index = index;
            this.slot = slot;
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

        // The coordinator's cluster overrides the local view — see the class doc. A member it named that is no
        // longer eligible is dropped, and then its numbering no longer holds, so the slot falls back to index.
        Published published = PUBLISHED.get(unit.getId());
        int publishedSlot = -1;
        if (published != null) {
            List<Member> resolved = resolvePublished(unit, hull, published, members);
            if (resolved != null) {
                members = resolved;
                if (resolved.size() == published.memberIds().length) publishedSlot = published.slot();
            }
        }

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

        return new Snapshot(members, index, publishedSlot >= 0 ? publishedSlot : index, cx, cy, cz,
                peerVehicle, playerNearby);
    }

    /**
     * The published cluster as live members, in id order. Locally scanned members are reused; the rest (the
     * coordinator clusters on centroid distance, so a member can be beyond this crew's own radius) are looked
     * up by id and re-checked for eligibility. Null if this unit itself is not among them.
     */
    @Nullable
    private static List<Member> resolvePublished(AbstractUnit unit, VehicleEntity hull, Published published,
                                                 List<Member> local) {
        java.util.Map<Integer, Member> byId = new java.util.HashMap<>();
        for (Member m : local) byId.put(m.unitId, m);
        List<Member> out = new ArrayList<>(published.memberIds().length);
        boolean self = false;
        for (int id : published.memberIds()) {
            Member m = byId.get(id);
            if (m == null && hull.level().getEntity(id) instanceof AbstractUnit other
                    && other.getVehicle() instanceof VehicleEntity otherHull
                    && otherHull.getFirstPassenger() == other
                    && isGroundIdleEligible(other, otherHull)
                    && VehicleTargeting.isFriendly(unit, other)) {
                m = new Member(other, otherHull);
            }
            if (m == null) continue;
            if (m.unitId == unit.getId()) self = true;
            out.add(m);
        }
        return self ? out : null;
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

    /**
     * Polygon slot offset — a {@code RING} from the formation kernel, laid in a frame facing +X so slot 0 sits
     * east of centre and the rest go round towards +Z, as this always has. Pure; the self-check pins it.
     */
    public static Vec3 holdSlotOffset(int index, int size, double baseRadius, double radiusMin,
                                      double radiusMax, double scrambleX, double scrambleZ) {
        int n = Math.min(Math.max(size, 1), MAX_HOLD_SIZE);
        double radius = Mth.clamp(baseRadius * (1.0 + 0.2 * (n - 1)), radiusMin, radiusMax);
        FormationSpec ring = new FormationSpec(FormationShape.RING, radius, 1.0, 1.0, 1, FormationSpec.Placement.TRAIL);
        return FormationGeometry.slot(new FormationFrame(scrambleX, 0.0, scrambleZ, 1.0, 0.0), ring, index, n);
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

    /**
     * Take up a hold slot. How long the group holds is no longer this hull's business: the coordinator keeps one
     * clock for the whole cluster and forms the march when it runs out ({@code IdleMarches}).
     */
    public static void enterHold(AbstractUnit unit, VehicleEntity hull, Snapshot snap) {
        hull.getPersistentData().putByte(MODE_KEY, MODE_HOLD);
        ensureScramble(unit, hull, snap);
    }

    private static void ensureScramble(AbstractUnit unit, VehicleEntity hull, Snapshot snap) {
        CompoundTag data = hull.getPersistentData();
        int leader = snap.leaderId;
        int index = snap.slot;
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

    /**
     * Fill the Facts idle-group fields. Travel is a march, and whether this crew is in one is the registry's answer
     * alone; the scorer only has to see it.
     */
    public static void applyToFacts(AbstractUnit unit, VehicleEntity hull, Snapshot snap, Facts facts) {
        facts.idleGroupSize = snap.size;
        facts.idleGroupIndex = snap.index;
        facts.idleLeaderId = snap.leaderId;
        facts.idleIsLeader = snap.isLeader;
        facts.idleGroupOversize = snap.size > MAX_HOLD_SIZE;
        facts.idlePeerVehicleNearby = snap.peerVehicleNearby;
        facts.idlePlayerNearby = snap.playerNearby;
        facts.idleSnapshot = snap;
        boolean marching = MarchRegistry.kindOf(unit.getId()) == March.Kind.IDLE_TRAVEL;
        facts.idleTravelActive = marching;
        facts.idleHoldExpired = marching;
        facts.idleHoldElapsed = marching ? 1.0 : 0.0;

        // Contact / orders wipe idle hold state.
        if ((unit.getTarget() != null || VehicleTargeting.underStandingOrder(unit))
                && !CrewAssignment.isIdleTasked(unit.getId())) {
            clear(hull);
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
        Vec3 off = holdSlotOffset(snap.slot, snap.size, formationBaseRadius(),
                formationRadiusMin(), formationRadiusMax(), sx, sz);
        double x = snap.centerX + off.x;
        double z = snap.centerZ + off.z;
        double y = cachedGroundY(hull, x, z, snap.centerY);
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

    static double groundY(Level level, double x, double z, double anchorY) {
        GROUND_Y_PROBES.increment();
        return GroundSnap.groundY(level, x, z, anchorY);
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

    private static double travelDetectRadius() {
        try { return SewvConfig.IDLE_TRAVEL_DETECT_RADIUS.get(); } catch (Throwable t) { return 20.0; }
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

    public static String modeLabel(byte mode) {
        return mode == MODE_HOLD ? "hold" : "none";
    }

    /** One-line snapshot for {@code /sewv debug idleStatus}. */
    public static String describeStatus(AbstractUnit unit, VehicleEntity hull, Snapshot snap) {
        long now = hull.level().getGameTime();
        BlockPos slot = holdDestination(unit, hull, null, snap);
        MarchRegistry.Slot march = MarchRegistry.slotFor(unit.getId(), now);
        String slotStr = slot != null ? slot.toShortString() : "-";
        String marchStr = march == null ? "-" : String.format(java.util.Locale.ROOT, "%s#%d %.0f,%.0f%s",
                march.kind(), march.index(), march.x(), march.z(), march.paused() ? " paused" : "");
        return String.format(java.util.Locale.ROOT,
                "unit#%d hull#%d mode=%s debugDrive=%s group=%d idx=%d leader=%d slot=%s march=%s",
                unit.getId(), hull.getId(), modeLabel(modeOf(hull)), isDebugDrive(hull),
                snap.size, snap.index, snap.leaderId, slotStr, marchStr);
    }
}
