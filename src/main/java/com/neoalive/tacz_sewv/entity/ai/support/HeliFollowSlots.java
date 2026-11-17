package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.formation.SlotMatcher;
import com.neoalive.tacz_sewv.heli.avoid.AvoidForce;

/**
 * Hold points for helicopters under a plain FOLLOW_ME: a ring round the commander, one slot each.
 *
 * <p>A FOLLOW_ME without a formation resolves to the commander's own block for every follower, so
 * two helicopters hovered on the same point and only the flight barrier kept them apart. Ground
 * hulls get spacing from their formation slots; helicopters are not eligible for formations, so
 * they get it here.
 *
 * <p><b>One membership list per commander, shared.</b> Each crew building its own list from its
 * own scan is how two idle crews once took the same slot. Here the first follower to ask in a
 * refresh window scans <i>around the commander</i> and matches every follower to a slot
 * ({@link SlotMatcher}, least travel, sticky to last window's slot so a re-laid ring does not
 * shuffle); every other follower reads that same table. Slots sit on fixed compass bearings, not
 * on the commander's heading, so a player turning on the spot does not swing the ring round.
 *
 * <p>A lone follower, a formation, or any destination that is not the plain-follow fallback is
 * passed through unchanged. Server thread only; network ids, so nothing persists.
 */
public final class HeliFollowSlots {

    /** Followers further than this from the commander are not in the ring. */
    private static final double SCAN_RADIUS = 160.0;
    private static final double SCAN_HEIGHT = 128.0;
    /** How long one membership table serves before the next follower re-scans. */
    private static final long REFRESH_TICKS = 20L;
    /** Tables untouched this long are dropped. */
    private static final long STALE_TICKS = 600L;
    /** Clear air between neighbouring hulls on top of their widths: the barrier gap each side, plus margin. */
    static final double NEIGHBOUR_CLEARANCE = 2.0 * AvoidForce.AIRFRAME_GAP + 2.0;

    private static final Map<UUID, Table> TABLES = new HashMap<>();

    private HeliFollowSlots() {}

    private static final class Table {
        long computedAt = Long.MIN_VALUE;
        long usedAt;
        /** hull id → slot. */
        final Map<Integer, Integer> slotOf = new HashMap<>();
        int count;
        double separation;
    }

    /**
     * Ring radius that puts {@code n} slots {@code separation} apart along the chord. Pure.
     * One or no follower has no ring.
     */
    public static double ringRadius(int n, double separation) {
        if (n < 2) return 0.0;
        return separation / (2.0 * Math.sin(Math.PI / n));
    }

    /** Slot {@code slot} of {@code n}: {@code {dx, dz}} from the commander, slot 0 due north. Pure. */
    public static double[] offset(int slot, int n, double separation) {
        double r = ringRadius(n, separation);
        if (r == 0.0) return new double[] {0.0, 0.0};
        double a = slot * (Math.PI * 2.0 / n);
        return new double[] {Math.sin(a) * r, -Math.cos(a) * r};
    }

    /**
     * {@code dest} moved onto this hull's ring slot when it is the plain FOLLOW_ME fallback (the
     * commander's own block), else {@code dest} unchanged.
     */
    public static BlockPos adjust(PmcUnitEntity pmc, VehicleEntity hull, BlockPos dest) {
        if (pmc.getOrder() != OrderType.FOLLOW_COMMANDER) return dest;
        if (VehicleFormation.forwardOf(pmc) != null) return dest; // a formation lays its own slots
        if (!(pmc.level() instanceof ServerLevel level)) return dest;
        UUID owner = pmc.getOwnerUUID();
        if (owner == null) return dest;
        Player commander = level.getPlayerByUUID(owner);
        if (commander == null || !dest.equals(commander.blockPosition())) return dest;

        Table t = table(level, owner, commander);
        Integer slot = t.slotOf.get(hull.getId());
        if (slot == null || t.count < 2) return dest;
        double[] o = offset(slot, t.count, t.separation);
        return BlockPos.containing(commander.getX() + o[0], commander.getY(), commander.getZ() + o[1]);
    }

    private static Table table(ServerLevel level, UUID owner, Player commander) {
        long now = level.getGameTime();
        Table t = TABLES.computeIfAbsent(owner, k -> new Table());
        t.usedAt = now;
        if (t.computedAt != Long.MIN_VALUE && now - t.computedAt < REFRESH_TICKS) return t;
        t.computedAt = now;
        rebuild(level, owner, commander, t);
        prune(now);
        return t;
    }

    private static void rebuild(ServerLevel level, UUID owner, Player commander, Table t) {
        AABB box = commander.getBoundingBox().inflate(SCAN_RADIUS, SCAN_HEIGHT, SCAN_RADIUS);
        List<VehicleEntity> hulls = new ArrayList<>(level.getEntitiesOfClass(VehicleEntity.class, box,
                v -> isFollower(v, owner)));
        hulls.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        int n = hulls.size();
        double widest = 0.0;
        for (VehicleEntity v : hulls) widest = Math.max(widest, v.getBbWidth());
        double separation = widest + NEIGHBOUR_CLEARANCE;

        Map<Integer, Integer> prev = new HashMap<>(t.slotOf);
        t.slotOf.clear();
        t.count = n;
        t.separation = separation;
        if (n < 2) return;

        double[] mx = new double[n];
        double[] mz = new double[n];
        double[] sx = new double[n];
        double[] sz = new double[n];
        int[] prevSlot = new int[n];
        for (int i = 0; i < n; i++) {
            VehicleEntity v = hulls.get(i);
            mx[i] = v.getX();
            mz[i] = v.getZ();
            double[] o = offset(i, n, separation);
            sx[i] = commander.getX() + o[0];
            sz[i] = commander.getZ() + o[1];
            Integer p = prev.get(v.getId());
            prevSlot[i] = p != null && p < n && prev.size() == n ? p : -1;
        }
        int[] slotOf = SlotMatcher.match(mx, mz, sx, sz, prevSlot, separation * separation);
        for (int i = 0; i < n; i++) t.slotOf.put(hulls.get(i).getId(), slotOf[i]);
    }

    /** A live helicopter driven by one of {@code owner}'s PMCs under a plain FOLLOW_ME. */
    private static boolean isFollower(VehicleEntity v, UUID owner) {
        if (v.isWreck() || !v.isAlive() || !HullFacts.isHelicopterHull(v)) return false;
        if (!(v.getFirstPassenger() instanceof PmcUnitEntity pilot)) return false;
        return owner.equals(pilot.getOwnerUUID())
                && pilot.getOrder() == OrderType.FOLLOW_COMMANDER
                && VehicleFormation.forwardOf(pilot) == null;
    }

    private static void prune(long now) {
        Iterator<Table> it = TABLES.values().iterator();
        while (it.hasNext()) {
            if (now - it.next().usedAt > STALE_TICKS) it.remove();
        }
    }

    /** Drop every table — server start/stop. */
    public static void clear() {
        TABLES.clear();
    }

    /** The slot table this hull currently reads, for the debug line: {@code "slot/count"} or null. */
    @Nullable
    public static String describe(UUID owner, int hullId) {
        Table t = TABLES.get(owner);
        if (t == null) return null;
        Integer s = t.slotOf.get(hullId);
        return s == null ? null : s + "/" + t.count;
    }
}
