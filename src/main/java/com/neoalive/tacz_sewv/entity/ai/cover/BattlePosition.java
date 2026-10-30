package com.neoalive.tacz_sewv.entity.ai.cover;

import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.PathBudget;

/**
 * A ground crew's battle position: a hull-down-where-possible spot on its engagement band, picked
 * once per engagement and held while the crew is working from it, with one pre-picked alternate to
 * move to when it is under pressure or cannot shoot. Owned by {@code TacticalPosture}; picked by
 * {@link CoverQuery#suggestBattlePositions}; driven to by {@code DriveVehicleGoal}.
 *
 * <p>An engagement is the crew's fresh-contact memory, not a target lock, so the flicker of locks
 * being lost and regained never re-picks. Picks are also bounded per hull ({@link #PICK_COOLDOWN})
 * and per level ({@link #PICKS_PER_TICK}, through {@link PathBudget#decide}).
 */
public final class BattlePosition {

    public enum Verdict { KEEP, SWAP, DROP }

    /** Game ticks between two picks by the same hull. */
    public static final long PICK_COOLDOWN = 200;
    /** Picks allowed per level per game tick; a refused hull asks again on its next refresh. */
    static final int PICKS_PER_TICK = 2;
    /** A refused hull picks regardless after this long, so none starves. */
    static final int PICK_MAX_WAIT = 40;
    /** Threat bearing drift, seen from the position, past which it no longer faces the fight. */
    public static final double MAX_DRIFT_RAD = Math.toRadians(45.0);
    /** {@code HullPressure} at which a crew on station gives the position up. */
    public static final double SWAP_PRESSURE = 0.5;
    /**
     * Ticks on station before pressure counts. Pressure has a 60-tick half-life, so a crew arriving
     * at its alternate still carries what drove it off the primary; by now that has decayed under
     * the threshold, and only fresh hits can trip it again.
     */
    public static final long SETTLE_TICKS = 40;
    /** How long a breaker-driven swap shields the crew from the breaker's own orbit. */
    public static final long BREAKER_SWAP_WINDOW = 160;
    /**
     * How long a position counts as working ({@link #working}) after arriving or after the last shot from it. Past
     * this a silent crew loses the hold bias, so doctrine (aggression, initiative, range) can move it on. Without
     * that, two sides on facing positions each held for the other to make the first move.
     */
    public static final long WORK_TICKS = 200;
    /** Same pad {@code DriveVehicleGoal} arrives at posture waypoints with. */
    public static final double ARRIVE_PAD = 3.0;

    private static final Map<Level, long[]> PICK_SLOTS = new WeakHashMap<>();

    @Nullable
    private Vec3 primary;
    @Nullable
    private Vec3 alternate;
    private boolean hullDown;
    private boolean swapped;
    private double pickedBearing;
    private long leaseUntil = Long.MIN_VALUE;
    private long arrivedAt = Long.MIN_VALUE;
    private long swappedAt = Long.MIN_VALUE;
    private long nextPickAt = Long.MIN_VALUE;
    private long refusedSince = Long.MIN_VALUE;

    /**
     * Keep, swap or drop a latched position. Pure: every input is a number, so the self-check runs
     * it headless. Checked in order, and the first answer wins. The breaker's swap is
     * {@link #onBreaker}, which runs on the drive tick rather than the ~1s refresh.
     *
     * @param onStationTicks ticks since arrival, or -1 while still en route
     * @param minDistance    {@code preferredRange - rangeDeadband}: inside it the position is behind the target
     */
    public static Verdict decide(boolean engaged, boolean blocked, double driftRad, double targetDistance,
                                 double minDistance, long now, long leaseUntil, long onStationTicks,
                                 double pressure, boolean hasAlternate) {
        if (!engaged || blocked) return Verdict.DROP;
        if (targetDistance < minDistance) return Verdict.DROP;
        if (driftRad > MAX_DRIFT_RAD) return Verdict.DROP;
        if (now > leaseUntil) return Verdict.DROP;
        boolean pressed = onStationTicks >= SETTLE_TICKS && pressure >= SWAP_PRESSURE;
        if (pressed) return hasAlternate ? Verdict.SWAP : Verdict.DROP;
        return Verdict.KEEP;
    }

    /** On station and either just arrived or firing from it: the only time the scorer should favour staying. */
    public static boolean working(long now, long arrivedAt, long lastShot) {
        if (arrivedAt == Long.MIN_VALUE) return false;
        if (now - arrivedAt <= WORK_TICKS) return true;
        return lastShot != Long.MIN_VALUE && lastShot >= arrivedAt && now - lastShot <= WORK_TICKS;
    }

    /** Lease renewed by a shot fired from station; never shortened. */
    public static long renewLease(long leaseUntil, long arrivedAt, long lastShot, long leaseTicks) {
        if (arrivedAt == Long.MIN_VALUE || lastShot == Long.MIN_VALUE || lastShot < arrivedAt) return leaseUntil;
        return Math.max(leaseUntil, lastShot + leaseTicks);
    }

    /** Absolute horizontal angle between two bearings, 0..PI. */
    public static double bearingDelta(double a, double b) {
        double d = Math.abs(a - b) % (Math.PI * 2.0);
        return d > Math.PI ? Math.PI * 2.0 - d : d;
    }

    @Nullable
    public Vec3 point() {
        return this.primary;
    }

    public boolean latched() {
        return this.primary != null;
    }

    public boolean hullDown() {
        return this.hullDown;
    }

    public boolean hasAlternate() {
        return this.alternate != null;
    }

    public boolean onStation() {
        return this.arrivedAt != Long.MIN_VALUE;
    }

    public long leaseUntil() {
        return this.leaseUntil;
    }

    public long arrivedAt() {
        return this.arrivedAt;
    }

    public double pickedBearing() {
        return this.pickedBearing;
    }

    public static double arriveRadius(VehicleEntity hull) {
        return hull.getBbWidth() * 0.5 + ARRIVE_PAD;
    }

    /** Record arrival the first time the hull is inside the arrival radius; never un-arrive. */
    public void markArrival(VehicleEntity hull, long now) {
        if (this.primary == null || this.arrivedAt != Long.MIN_VALUE) return;
        double r = arriveRadius(hull);
        double dx = this.primary.x - hull.getX();
        double dz = this.primary.z - hull.getZ();
        if (dx * dx + dz * dz <= r * r) this.arrivedAt = now;
    }

    public void renew(long lastShot) {
        this.leaseUntil = renewLease(this.leaseUntil, this.arrivedAt, lastShot, leaseTicks());
    }

    public boolean mayPick(long now) {
        return this.primary == null && now >= this.nextPickAt;
    }

    /**
     * The per-level pick budget. On true the slot is counted; on false the hull's wait starts, so
     * it is bounded by {@link #PICK_MAX_WAIT}.
     */
    public boolean acquirePickSlot(Level level, long now) {
        long[] slot = PICK_SLOTS.computeIfAbsent(level, l -> new long[] {Long.MIN_VALUE, 0});
        if (slot[0] != now) {
            slot[0] = now;
            slot[1] = 0;
        }
        long waited = this.refusedSince == Long.MIN_VALUE ? 0 : now - this.refusedSince;
        if (!PathBudget.decide((int) slot[1], PICKS_PER_TICK, waited, PICK_MAX_WAIT, false)) {
            if (this.refusedSince == Long.MIN_VALUE) this.refusedSince = now;
            return false;
        }
        slot[1]++;
        this.refusedSince = Long.MIN_VALUE;
        return true;
    }

    /** Latch a fresh pick, or just start the cooldown when there was nothing worth taking. */
    public void latch(@Nullable CoverQuery.BattlePick pick, double bearing, long now) {
        this.nextPickAt = now + PICK_COOLDOWN;
        if (pick == null) return;
        this.primary = pick.primary();
        this.alternate = pick.alternate();
        this.hullDown = pick.hullDown();
        this.swapped = false;
        this.pickedBearing = bearing;
        this.leaseUntil = now + leaseTicks();
        this.arrivedAt = Long.MIN_VALUE;
        this.swappedAt = Long.MIN_VALUE;
    }

    /** Move to the alternate. False (and nothing changed) when there is none left. */
    public boolean swap(long now) {
        if (this.alternate == null) return false;
        this.primary = this.alternate;
        this.alternate = null;
        this.hullDown = false; // only the primary was tested from the hull's height
        this.swapped = true;
        this.swappedAt = now;
        this.arrivedAt = Long.MIN_VALUE;
        this.leaseUntil = now + leaseTicks();
        return true;
    }

    /**
     * The stalemate breaker fired. The first time, the crew swaps instead of orbiting; for the rest
     * of that breaker episode the swap still stands. Answers whether the position survives.
     */
    public boolean onBreaker(long now) {
        if (this.primary == null) return false;
        if (!this.swapped && swap(now)) return true;
        if (this.swapped && now - this.swappedAt <= BREAKER_SWAP_WINDOW) return true;
        drop(now);
        return false;
    }

    public void drop(long now) {
        if (this.primary != null) this.nextPickAt = Math.max(this.nextPickAt, now + PICK_COOLDOWN);
        this.primary = null;
        this.alternate = null;
        this.hullDown = false;
        this.swapped = false;
        this.arrivedAt = Long.MIN_VALUE;
        this.swappedAt = Long.MIN_VALUE;
        this.leaseUntil = Long.MIN_VALUE;
    }

    public void clear() {
        drop(Long.MIN_VALUE);
        this.nextPickAt = Long.MIN_VALUE;
        this.refusedSince = Long.MIN_VALUE;
    }

    public static boolean enabled() {
        try {
            return SewvConfig.BATTLE_POSITIONS_ENABLED.get();
        } catch (Throwable unbaked) {
            return true;
        }
    }

    static long leaseTicks() {
        try {
            return SewvConfig.BATTLE_POSITION_LEASE_TICKS.get();
        } catch (Throwable unbaked) {
            return 600;
        }
    }
}
