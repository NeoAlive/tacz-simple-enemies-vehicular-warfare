package com.neoalive.tacz_sewv.entity.ai.march;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.formation.SlotMatcher;
import com.neoalive.tacz_sewv.entity.ai.support.InfantryOrders;
import com.neoalive.tacz_sewv.entity.ai.support.InfantrySquads;

/**
 * When an idle group sets off, and how: the group-level half of idle travel.
 *
 * <p><b>The group decides once, together.</b> Each crew used to roll its own hold timer and its own scorer chose
 * when to leave, so a group set off one hull at a time — or the first one went and the rest never noticed. Now
 * the coordinator keeps one clock per unit ({@link #note}: when it was first seen idle) and a cluster is ready
 * when its longest-idle member has waited its hold out ({@link #ready}). The hold's length is derived from the
 * cluster's leader id, so it is deterministic and the same for every member. Then {@link #form} lays one march
 * for the whole cluster in a single pass.
 *
 * <p>RU/US only. A PMC's crews are the player's; they hold, and never drive off on their own.
 */
public final class IdleMarches {

    /**
     * Per dimension: unit id → game time it was first seen idle and unmarching, this stretch. Per dimension
     * because the coordinator prunes it once per level per pass; one shared map was wiped by the Nether's empty
     * pass straight after the overworld's, so no hold ever ran out and no group ever set off on its own.
     */
    private static final Map<ResourceKey<Level>, Map<Integer, Long>> IDLE_SINCE = new HashMap<>();

    private IdleMarches() {}

    public static void clear() {
        IDLE_SINCE.clear();
    }

    /** Note {@code ids} as idle at {@code now} (keeps the earliest time). */
    public static void note(ServerLevel level, int[] ids, long now) {
        Map<Integer, Long> since = clocks(level);
        for (int id : ids) since.putIfAbsent(id, now);
    }

    /** Forget every unit not seen idle this pass, so the next idle stretch starts its hold afresh. */
    public static void retain(ServerLevel level, Set<Integer> seen) {
        clocks(level).keySet().retainAll(seen);
    }

    /** Has the cluster {@code ids} (sorted; ids[0] leads) held long enough to set off? */
    public static boolean ready(ServerLevel level, int[] ids, long now) {
        Map<Integer, Long> clocks = clocks(level);
        long since = Long.MAX_VALUE;
        for (int id : ids) since = Math.min(since, clocks.getOrDefault(id, now));
        return now - since >= holdTicks(ids[0]);
    }

    /** Mark {@code ids} as having held for ever — the debug "expire hold". */
    public static void expire(ServerLevel level, int[] ids, long now) {
        Map<Integer, Long> clocks = clocks(level);
        for (int id : ids) clocks.put(id, now - 1_000_000L);
    }

    private static Map<Integer, Long> clocks(ServerLevel level) {
        return IDLE_SINCE.computeIfAbsent(level.dimension(), d -> new HashMap<>());
    }

    /** The hold before a cluster led by {@code leader} sets off: between the configured min and max, by id. */
    static int holdTicks(int leader) {
        int min;
        int max;
        try {
            min = SewvConfig.IDLE_HOLD_MIN_TICKS.get();
            max = SewvConfig.IDLE_HOLD_MAX_TICKS.get();
        } catch (Throwable t) {
            min = 100;
            max = 200;
        }
        int span = Math.max(1, max - min);
        return min + (int) Math.floorMod(leader * 0x9E3779B9L, (long) span);
    }

    public static boolean factionTravels(CrewFacts.Faction faction) {
        return faction == CrewFacts.Faction.RU || faction == CrewFacts.Faction.US;
    }

    /**
     * Form an idle-travel march out of {@code drivers} (each driving its own ground hull). Null when there is
     * no road to take from here — the group keeps holding and tries again on a later pass.
     */
    @Nullable
    public static March form(ServerLevel level, List<AbstractUnit> drivers, CrewFacts.Faction faction, long now) {
        return form(level, drivers, faction, now, Double.NaN);
    }

    /** As {@link #form(ServerLevel, List, CrewFacts.Faction, long)}, on {@code bearing} unless it is NaN. */
    @Nullable
    public static March form(ServerLevel level, List<AbstractUnit> drivers, CrewFacts.Faction faction, long now,
                             double bearing) {
        if (drivers.isEmpty()) return null;
        List<AbstractUnit> sorted = new ArrayList<>(drivers);
        sorted.sort(Comparator.comparingInt(AbstractUnit::getId));
        int n = sorted.size();
        int leader = sorted.get(0).getId();

        double cx = 0.0;
        double cz = 0.0;
        double widest = 0.0;
        for (AbstractUnit u : sorted) {
            cx += u.getVehicle().getX();
            cz += u.getVehicle().getZ();
            widest = Math.max(widest, u.getVehicle().getBbWidth());
        }
        cx /= n;
        cz /= n;

        if (Double.isNaN(bearing)) bearing = awayFromHostilePlayer(level, sorted.get(0), cx, cz);
        if (Double.isNaN(bearing)) bearing = RouteScout.openBearing(level, cx, cz, RouteScout.seedAngle(leader));
        double fx = Math.sin(bearing);
        double fz = Math.cos(bearing);

        // The column is laid centred on the group, head forward: its tail is the road's start.
        double spacing = widest + gap();
        double progress = (n - 1) * spacing;
        MarchRoute route = new MarchRoute(cx - fx * progress * 0.5, cz - fz * progress * 0.5, fx, fz);
        double maxLength = progress + leadDistance();
        RouteScout.extend(level, route, bearing, progress + MarchRegistry.ROAD_AHEAD, maxLength);
        if (route.length() < progress + RouteScout.LEG) {
            SewvDiag.command("march refused leader={} bearing={} road={} (no drivable ground ahead)", leader,
                    deg(bearing), fmt(route.length()));
            return null;
        }

        // Who takes which place in the column: least total travel, so nobody drives through the others to the head.
        double[] mx = new double[n];
        double[] mz = new double[n];
        double[] sx = new double[n];
        double[] sz = new double[n];
        int[] prev = new int[n];
        Arrays.fill(prev, -1);
        for (int i = 0; i < n; i++) {
            mx[i] = sorted.get(i).getVehicle().getX();
            mz[i] = sorted.get(i).getVehicle().getZ();
            double[] slot = MarchGeometry.column(route, progress, i, spacing);
            sx[i] = slot[0];
            sz[i] = slot[1];
        }
        int[] slotOf = SlotMatcher.match(mx, mz, sx, sz, prev, 0.0);
        int[] hulls = new int[n];
        for (int i = 0; i < n; i++) hulls[slotOf[i]] = sorted.get(i).getId();

        int[] squads = recruitSquads(level, faction, cx, sorted.get(0).getY(), cz);
        March march = MarchRegistry.register(level, faction, March.Kind.IDLE_TRAVEL, hulls, squads, route,
                bearing, maxLength, spacing, MarchRegistry.pace(), progress, now);
        SewvDiag.command("march formed id={} kind={} faction={} hulls={} squadLeaders={} bearing={} spacing={} road={}",
                march.id, march.kind, faction, Arrays.toString(hulls), Arrays.toString(squads), deg(bearing),
                fmt(spacing), fmt(route.length()));
        return march;
    }

    /**
     * The idle infantry of {@code faction} around the group, as squads to trail the column: returns the squad
     * leaders' ids, which take the march places. Whole squads only — a squad whose leader is alive but busy or out
     * of reach stays with it. Riflemen with no squad are stamped into squads first ({@link InfantrySquads#stamp});
     * one left over on its own walks as a squad of one. Members keep following their leader by
     * {@code InfantrySquadGoal}, which is what keeps the infantry a loose body rather than a second column.
     */
    static int[] recruitSquads(ServerLevel level, CrewFacts.Faction faction, double cx, double cy, double cz) {
        double r = groupRadius();
        AABB box = new AABB(cx - r, cy - 16.0, cz - r, cx + r, cy + 16.0, cz + r);
        List<AbstractUnit> recruits = level.getEntitiesOfClass(AbstractUnit.class, box, u -> u.isAlive()
                && !(u instanceof PmcUnitEntity)
                && CrewFacts.factionOfCrew(u) == faction
                && InfantryOrders.free(u)
                && !MarchRegistry.isMarching(u.getId())
                && (u.getX() - cx) * (u.getX() - cx) + (u.getZ() - cz) * (u.getZ() - cz) <= r * r);
        if (recruits.isEmpty()) return new int[0];
        recruits.sort(Comparator.comparingInt(AbstractUnit::getId));

        List<AbstractUnit> loose = new ArrayList<>();
        for (AbstractUnit u : recruits) {
            if (InfantrySquads.squadOf(u) == null) loose.add(u);
        }
        InfantrySquads.stamp(loose);

        Map<UUID, List<AbstractUnit>> squads = new HashMap<>();
        List<Integer> leaders = new ArrayList<>();
        for (AbstractUnit u : recruits) {
            UUID squad = InfantrySquads.squadOf(u);
            if (squad == null) leaders.add(u.getId()); // a squad of one
            else squads.computeIfAbsent(squad, k -> new ArrayList<>()).add(u);
        }
        for (Map.Entry<UUID, List<AbstractUnit>> e : squads.entrySet()) {
            List<AbstractUnit> members = e.getValue();
            if (level.getEntity(e.getKey()) instanceof AbstractUnit lead && lead.isAlive()) {
                if (members.contains(lead)) leaders.add(lead.getId());
                continue; // its leader is busy or out of reach: the squad stays with it
            }
            // The leader is gone: the lowest id takes the squad, as InfantrySquads would on its own.
            AbstractUnit heir = members.get(0);
            for (AbstractUnit m : members) m.getPersistentData().putUUID(InfantrySquads.TAG_SQUAD, heir.getUUID());
            leaders.add(heir.getId());
        }
        leaders.sort(Integer::compare);
        int[] out = new int[leaders.size()];
        for (int i = 0; i < out.length; i++) out[i] = leaders.get(i);
        return out;
    }

    /** Bearing straight away from the nearest hostile player in reach of the group, or NaN. */
    private static double awayFromHostilePlayer(ServerLevel level, AbstractUnit head, double cx, double cz) {
        double r = detectRadius() * 3.0;
        Player closest = null;
        double best = r * r;
        AABB box = new AABB(cx - r, head.getY() - 32.0, cz - r, cx + r, head.getY() + 32.0, cz + r);
        for (Player p : level.getEntitiesOfClass(Player.class, box)) {
            if (!p.isAlive() || p.isCreative() || p.isSpectator()) continue;
            if (VehicleTargeting.isNonHostile(head, p)) continue;
            double dx = p.getX() - cx;
            double dz = p.getZ() - cz;
            if (dx * dx + dz * dz < best) {
                best = dx * dx + dz * dz;
                closest = p;
            }
        }
        return closest == null ? Double.NaN : Math.atan2(cx - closest.getX(), cz - closest.getZ());
    }

    /** True when {@code unit} drives a ground hull of its own — the only thing that can take a column slot. */
    public static boolean drives(AbstractUnit unit) {
        return unit.getVehicle() instanceof VehicleEntity hull && hull.getFirstPassenger() == unit && !hull.isWreck();
    }

    private static double groupRadius() {
        try { return SewvConfig.IDLE_GROUP_RADIUS.get(); } catch (Throwable t) { return 50.0; }
    }

    private static double gap() {
        try {
            return (SewvConfig.IDLE_TRAVEL_SPACING_MIN.get() + SewvConfig.IDLE_TRAVEL_SPACING_MAX.get()) * 0.5;
        } catch (Throwable t) {
            return 6.5;
        }
    }

    private static double leadDistance() {
        try { return SewvConfig.IDLE_TRAVEL_LEAD_DISTANCE.get(); } catch (Throwable t) { return 500.0; }
    }

    private static double detectRadius() {
        try { return SewvConfig.IDLE_TRAVEL_DETECT_RADIUS.get(); } catch (Throwable t) { return 20.0; }
    }

    private static String deg(double rad) {
        return String.format(Locale.ROOT, "%.0f", Math.toDegrees(rad));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }
}
