package com.neoalive.tacz_sewv.entity.ai.march;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Every live {@link March}, and the one answer to "is this unit marching, and where is its slot?".
 *
 * <p>The command coordinator forms marches and calls {@link #tick} once per pass; that is where a march is
 * paced, held together, extended and ended. Goals only ever read {@link #slotFor} — a map lookup and a little
 * arithmetic — so a marching unit costs nothing per tick that a held one does not.
 *
 * <p><b>Membership is frozen.</b> A march never re-derives who is in it from where anyone stands; that is what
 * broke idle travel, where the column was re-clustered by position every pass and fell apart the moment it
 * stretched. A member leaves only by dying, unloading, dismounting, or being given something else to do. Server
 * thread only; cleared on server start and stop.
 */
public final class MarchRegistry {

    private static final Map<Integer, Entry> MARCHES = new HashMap<>();
    /** unit id → its march; the index into that march's hull or infantry array is the slot. */
    private static final Map<Integer, Member> MEMBERS = new HashMap<>();
    private static int nextId = 1;

    /** Road kept laid ahead of the anchor. */
    static final double ROAD_AHEAD = 96.0;
    /** A hull this many spacings behind its slot holds the march; it resumes once all are inside the lower bound. */
    private static final double HULL_STRAGGLE_SPACINGS = 1.5;
    private static final double RESUME_FRACTION = 0.5;
    /** Of the resume line: a member this far inside it is in place, with margin so it does not stop on the edge. */
    private static final double IN_PLACE_FRACTION = 0.8;

    private MarchRegistry() {}

    private record Entry(March march, ResourceKey<Level> dimension, CrewFacts.Faction faction) {}

    private record Member(int marchId, boolean hull) {}

    /** One member's slot at a moment: where to be, which way the march is heading, and how it is moving. */
    public record Slot(double x, double z, double fx, double fz, boolean paused, double pace, double spacing,
                       March.Kind kind, boolean hull, int index, double straggleLimit) {

        /** How far {@code (hx, hz)} is behind this slot along the march heading; negative when ahead. */
        public double lag(double hx, double hz) {
            return (this.x - hx) * this.fx + (this.z - hz) * this.fz;
        }

        /** How far behind its place the registry counts {@code (hx, hz)}: the measure the march waits on. */
        public double behind(double hx, double hz) {
            return MarchRegistry.behind(hx, hz, new double[] {this.x, this.z, this.fx, this.fz});
        }

        /**
         * Close enough that a waiting march is not waiting on this member. Such a member stops where it stands
         * rather than closing onto the exact slot and squaring up: when every member did that on every pause, the
         * whole group shuffled and pivoted on the spot while one straggler caught up.
         */
        public boolean caughtUp(double hx, double hz) {
            return behind(hx, hz) <= this.straggleLimit * RESUME_FRACTION * IN_PLACE_FRACTION;
        }
    }

    public static void clear() {
        MARCHES.clear();
        MEMBERS.clear();
        nextId = 1;
    }

    public static boolean isMarching(int unitId) {
        return MEMBERS.containsKey(unitId);
    }

    /** The kind of march {@code unitId} is in, or null. */
    @Nullable
    public static March.Kind kindOf(int unitId) {
        Member m = MEMBERS.get(unitId);
        if (m == null) return null;
        Entry e = MARCHES.get(m.marchId);
        return e == null ? null : e.march.kind;
    }

    /** The march {@code unitId} belongs to, or null. */
    @Nullable
    public static March marchOf(int unitId) {
        Member m = MEMBERS.get(unitId);
        if (m == null) return null;
        Entry e = MARCHES.get(m.marchId);
        return e == null ? null : e.march;
    }

    /** {@code unitId}'s slot at {@code now}, or null when it is not marching. */
    @Nullable
    public static Slot slotFor(int unitId, long now) {
        Member m = MEMBERS.get(unitId);
        if (m == null) return null;
        Entry e = MARCHES.get(m.marchId);
        if (e == null) return null;
        March march = e.march;
        int[] ids = m.hull ? march.hulls : march.infantry;
        int index = indexOf(ids, unitId);
        if (index < 0) return null;
        double[] p = m.hull ? march.hullSlot(index, now) : march.infantrySlot(index, now);
        return new Slot(p[0], p[1], p[2], p[3], march.paused(), march.pace, march.spacing, march.kind, m.hull,
                index, m.hull ? hullLimit(march) : straggleDistance());
    }

    /**
     * Register a march. {@code hulls} are driver ids already in slot order; any of them already marching is
     * taken out of its old march first, so a unit is never in two.
     */
    public static March register(ServerLevel level, CrewFacts.Faction faction, March.Kind kind, int[] hulls,
                                 int[] infantry, MarchRoute route, double bearing, double maxLength,
                                 double spacing, double pace, double progress, long now) {
        // A unit is in one march at most: forming a new one around it supersedes the old outright.
        for (int[] ids : new int[][] {hulls, infantry}) {
            for (int id : ids) {
                Member m = MEMBERS.get(id);
                if (m != null) end(m.marchId, "re-formed");
            }
        }
        March march = new March(nextId++, kind, hulls.clone(), infantry.clone(), route, bearing, maxLength, spacing,
                pace, progress, now);
        MARCHES.put(march.id, new Entry(march, level.dimension(), faction));
        for (int id : march.hulls) MEMBERS.put(id, new Member(march.id, true));
        for (int id : march.infantry) MEMBERS.put(id, new Member(march.id, false));
        return march;
    }

    /** End a march: every member is released to whatever it would otherwise be doing. */
    public static void end(int marchId, String reason) {
        Entry e = MARCHES.remove(marchId);
        if (e == null) return;
        for (int id : e.march.hulls) MEMBERS.remove(id);
        for (int id : e.march.infantry) MEMBERS.remove(id);
        SewvDiag.command("march end id={} kind={} reason={}", marchId, e.march.kind, reason);
    }

    /** Take {@code unitId} out of its march; a march left with no hull ends. */
    public static void leave(int unitId) {
        Member m = MEMBERS.remove(unitId);
        if (m == null) return;
        Entry e = MARCHES.get(m.marchId);
        if (e == null) return;
        March march = e.march;
        if (m.hull) march.hulls = without(march.hulls, unitId);
        else march.infantry = without(march.infantry, unitId);
        if (march.hulls.length == 0) end(march.id, "no hulls");
    }

    /** Live marches on {@code level}, for the coordinator's publishing. */
    public static List<March> marchesOn(ServerLevel level) {
        List<March> out = new ArrayList<>();
        for (Entry e : MARCHES.values()) {
            if (e.dimension == level.dimension()) out.add(e.march);
        }
        return out;
    }

    /**
     * Pace, hold together, extend and end every march on {@code level}. Once per command pass.
     */
    public static void tick(ServerLevel level, long now) {
        // A copy: dropping a member can end its march, which removes it from the map being walked.
        for (Entry e : new ArrayList<>(MARCHES.values())) {
            if (e.dimension != level.dimension() || !MARCHES.containsKey(e.march.id)) continue;
            String reason = update(level, e, now);
            if (reason != null) end(e.march.id, reason);
        }
    }

    /** @return why the march must end, or null to keep going */
    @Nullable
    private static String update(ServerLevel level, Entry e, long now) {
        March march = e.march;
        // Drop members that are gone or have something else to do. A contact anywhere ends an idle march outright:
        // the whole group goes back to fighting, not just the one that saw something.
        List<AbstractUnit> drivers = new ArrayList<>(march.hulls.length);
        for (int id : march.hulls.clone()) {
            AbstractUnit u = driver(level, id);
            if (u == null) {
                leave(id);
                continue;
            }
            if (march.kind == March.Kind.IDLE_TRAVEL
                    && (u.getTarget() != null || VehicleTargeting.underStandingOrder(u))) {
                return "contact unit=" + id;
            }
            drivers.add(u);
        }
        if (!MARCHES.containsKey(march.id) || drivers.isEmpty()) return "no hulls";
        List<AbstractUnit> walkers = new ArrayList<>(march.infantry.length);
        for (int id : march.infantry.clone()) {
            AbstractUnit u = walker(level, id);
            if (u == null) {
                leave(id);
                continue;
            }
            if (march.kind == March.Kind.IDLE_TRAVEL && u.getTarget() != null) return "contact unit=" + id;
            walkers.add(u);
        }

        if (march.kind == March.Kind.IDLE_TRAVEL) {
            String hostile = hostileAhead(level, drivers.get(0), e.faction, march);
            if (hostile != null) return hostile;
        }

        // Cohesion: the anchor waits for whoever is furthest behind. Hysteresis so it does not stutter on the edge.
        double hullLimit = hullLimit(march);
        double footLimit = straggleDistance();
        double worst = 0.0;
        StringBuilder lags = SewvDiag.collectiveAiVerbose() ? new StringBuilder() : null;
        for (int k = 0; k < march.hulls.length; k++) {
            AbstractUnit u = drivers.get(k);
            double lag = behind(u.getVehicle().getX(), u.getVehicle().getZ(), march.hullSlot(k, now));
            worst = Math.max(worst, lag / hullLimit);
            if (lags != null) appendLag(lags, u.getId(), lag, u.getVehicle());
        }
        for (int i = 0; i < march.infantry.length; i++) {
            AbstractUnit u = walkers.get(i);
            double lag = behind(u.getX(), u.getZ(), march.infantrySlot(i, now));
            worst = Math.max(worst, lag / footLimit);
            if (lags != null) appendLag(lags, u.getId(), lag, u);
        }
        boolean pause = March.shouldPause(march.paused(), worst, RESUME_FRACTION);
        if (pause != march.paused()) march.setPaused(pause, now);
        if (march.stalledFor(worst, now) > stallTicks()) return "stall";

        if (!march.routeClosed) {
            double s = march.anchorAt(now);
            RouteScout.Result r = RouteScout.extend(level, march.route, march.bearing, s + ROAD_AHEAD, march.maxLength);
            if (r == RouteScout.Result.CLOSED) march.routeClosed = true;
        }
        if (march.arrived(now)) return "arrived";

        if (lags != null) {
            SewvDiag.command("march id={} kind={} s={} route={}{} paused={} worst={} lag=[{}]", march.id, march.kind,
                    fmt(march.anchorAt(now)), fmt(march.route.length()), march.routeClosed ? "(closed)" : "",
                    march.paused(), fmt(worst), lags.toString().trim());
        }
        return null;
    }

    /** A hostile player or hostile-crewed hull near the head of the column. Drones, mortars and empty hulls do not count. */
    @Nullable
    private static String hostileAhead(ServerLevel level, AbstractUnit head, CrewFacts.Faction faction, March march) {
        Entity hull = head.getVehicle();
        if (hull == null) return null;
        double r = detectRadius();
        AABB box = hull.getBoundingBox().inflate(r, 8.0, r);
        for (Player p : level.getEntitiesOfClass(Player.class, box)) {
            if (!p.isAlive() || p.isCreative() || p.isSpectator()) continue;
            if (VehicleTargeting.isNonHostile(head, p)) continue;
            return "hostile player=" + p.getId();
        }
        for (VehicleEntity v : level.getEntitiesOfClass(VehicleEntity.class, box, v -> !v.isWreck())) {
            if (v == hull) continue;
            CrewFacts.Faction f = CrewFacts.factionOf(v);
            if (f == null || f == faction) continue;
            if (v.getFirstPassenger() instanceof AbstractUnit crew && VehicleTargeting.isNonHostile(head, crew)) continue;
            return "hostile hull=" + v.getId();
        }
        return null;
    }

    private static double hullLimit(March march) {
        return Math.max(straggleDistance(), HULL_STRAGGLE_SPACINGS * march.spacing);
    }

    /** How far {@code (x, z)} is behind {@code slot}: along-track lag, or half the sideways miss if that is more. */
    static double behind(double x, double z, double[] slot) {
        double dx = slot[0] - x;
        double dz = slot[1] - z;
        double along = dx * slot[2] + dz * slot[3];
        double across = Math.abs(dx * -slot[3] + dz * slot[2]);
        return Math.max(along, across * 0.5);
    }

    private static void appendLag(StringBuilder sb, int id, double lag, Entity e) {
        double v = e.getDeltaMovement().horizontalDistance();
        sb.append(' ').append(id).append(':').append(fmt(lag)).append('@').append(fmt(v));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** The driver of hull slot {@code id}, if it is still alive, aboard and driving. */
    @Nullable
    private static AbstractUnit driver(ServerLevel level, int id) {
        if (!(level.getEntity(id) instanceof AbstractUnit u) || !u.isAlive()) return null;
        if (!(u.getVehicle() instanceof VehicleEntity hull) || hull.isWreck() || hull.getFirstPassenger() != u) return null;
        return u;
    }

    @Nullable
    private static AbstractUnit walker(ServerLevel level, int id) {
        if (!(level.getEntity(id) instanceof AbstractUnit u) || !u.isAlive() || u.isPassenger()) return null;
        return u;
    }

    private static int indexOf(int[] ids, int id) {
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == id) return i;
        }
        return -1;
    }

    private static int[] without(int[] ids, int id) {
        int i = indexOf(ids, id);
        if (i < 0) return ids;
        int[] out = new int[ids.length - 1];
        System.arraycopy(ids, 0, out, 0, i);
        System.arraycopy(ids, i + 1, out, i, ids.length - i - 1);
        return out;
    }

    /** Anchor speed, blocks per tick. Infantry-safe by default: below SEM's walk. */
    public static double pace() {
        try { return SewvConfig.MARCH_PACE.get(); } catch (Throwable t) { return 0.14; }
    }

    static double straggleDistance() {
        try { return SewvConfig.MARCH_STRAGGLE_DISTANCE.get(); } catch (Throwable t) { return 12.0; }
    }

    private static double detectRadius() {
        try { return SewvConfig.IDLE_TRAVEL_DETECT_RADIUS.get(); } catch (Throwable t) { return 20.0; }
    }

    private static int stallTicks() {
        try { return SewvConfig.IDLE_TRAVEL_STUCK_TICKS.get(); } catch (Throwable t) { return 600; }
    }
}
