package com.neoalive.tacz_sewv.entity.ai.command;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.utility.Facts;
import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;
import com.neoalive.tacz_sewv.util.CrewFacts;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side owner of battle groups: scans eligible drivers on the utility cadence, battle-gates,
 * runs the pure {@link Grouping} core, rebuilds per-group {@link InfluenceMap}/{@link BattleField},
 * then elects commanders.
 *
 * <p>Influence maps are rebuilt only here (command cadence) and only for live battle-gated groups.
 * Cell arrays are reused on each {@link BattleGroup}.
 */
public final class CommandCoordinator {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static int nextScan = Integer.MIN_VALUE;
    private static int nextGroupId = 1;

    private static final Map<ResourceKey<Level>, Map<Integer, BattleGroup>> GROUPS_BY_LEVEL = new HashMap<>();

    private CommandCoordinator() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        nextScan = Integer.MIN_VALUE;
        nextGroupId = 1;
        GROUPS_BY_LEVEL.clear();
        CommandEligibility.clearCache();
        CrewAssignment.clearAll();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        nextScan = Integer.MIN_VALUE;
        GROUPS_BY_LEVEL.clear();
        CommandEligibility.clearCache();
        CrewAssignment.clearAll();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        int now = event.getServer().getTickCount();
        if (now < nextScan) return;
        int interval;
        try {
            interval = SewvConfig.UTILITY_REFRESH_INTERVAL_TICKS.get();
        } catch (Throwable ignored) {
            return;
        }
        nextScan = now + interval;

        GroupParams params;
        int maxUnits;
        double engagement;
        try {
            params = new GroupParams(
                    SewvConfig.COMMAND_GROUP_JOIN_RADIUS.get(),
                    SewvConfig.COMMAND_GROUP_LEAVE_RADIUS.get(),
                    SewvConfig.COMMAND_GROUP_MAX_DIAMETER.get(),
                    SewvConfig.COMMAND_GROUP_MIN_SIZE.get());
            maxUnits = SewvConfig.COMMAND_MAX_UNITS.get();
            engagement = SewvConfig.COMMAND_ENGAGEMENT_RADIUS.get();
        } catch (Throwable ignored) {
            return;
        }
        if (params.leaveRadius <= params.joinRadius) {
            LOGGER.warn("[sewv-command] leaveRadius must exceed joinRadius; skipping scan");
            return;
        }
        if (params.leaveRadius > params.maxRadius()) {
            LOGGER.warn("[sewv-command] leaveRadius exceeds maxDiameter/2; hysteresis band is clipped by diameter");
        }

        for (ServerLevel level : event.getServer().getAllLevels()) {
            scanLevel(level, params, maxUnits, engagement, now);
        }
        syncCrewAssignments();
    }

    /** Publish every live role into {@link CrewAssignment}; drop units no longer tasked. */
    private static void syncCrewAssignments() {
        Set<Integer> keep = new HashSet<>();
        for (Map<Integer, BattleGroup> levelGroups : GROUPS_BY_LEVEL.values()) {
            for (BattleGroup g : levelGroups.values()) {
                Roles roles = g.currentRoles();
                if (roles == null) continue;
                for (Assignment a : roles.assignments) {
                    CrewAssignment.publish(a);
                    keep.add(a.unitId);
                }
            }
        }
        CrewAssignment.retainAll(keep);
    }

    /** Snapshot of live groups across all dimensions — Stage 2+ read from here. */
    public static Map<ResourceKey<Level>, Map<Integer, BattleGroup>> groupsView() {
        Map<ResourceKey<Level>, Map<Integer, BattleGroup>> copy = new HashMap<>();
        for (var e : GROUPS_BY_LEVEL.entrySet()) {
            copy.put(e.getKey(), Map.copyOf(e.getValue()));
        }
        return copy;
    }

    /**
     * Read-only map-debug lookup: which battle group a driver belongs to, and whether it is
     * the elected commander. Does not mutate grouping or election state.
     *
     * <p>{@code commander == false} with a non-null result means in-group but not commander —
     * including the deferred-election case where the group has no {@code commanderId} yet.
     */
    @Nullable
    public static CommandTag tagForDriver(int driverId) {
        for (Map<Integer, BattleGroup> levelGroups : GROUPS_BY_LEVEL.values()) {
            for (BattleGroup g : levelGroups.values()) {
                if (!g.contains(driverId)) continue;
                boolean commander = g.hasCommander() && g.commanderId() == driverId;
                return new CommandTag(g.groupId(), commander);
            }
        }
        return null;
    }

    /** Immutable view for map markers — never write through this. */
    public record CommandTag(int groupId, boolean commander) {}

    /**
     * Read-only debug snapshots of populated battlefields across all dimensions. Copies field
     * values only — does not rebuild influence or mutate groups.
     */
    public static List<BattleFieldDebug> battleFieldsDebug() {
        List<BattleFieldDebug> out = new ArrayList<>();
        for (var e : GROUPS_BY_LEVEL.entrySet()) {
            ResourceKey<Level> dim = e.getKey();
            for (BattleGroup g : e.getValue().values()) {
                BattleField bf = g.battleField();
                if (!bf.populated) continue;
                out.add(new BattleFieldDebug(
                        g.groupId(), dim,
                        bf.friendlyCentroidX, bf.friendlyCentroidZ,
                        bf.enemyCentroidX, bf.enemyCentroidZ,
                        bf.axisX, bf.axisZ,
                        bf.openFlankLeft, bf.openFlankRight,
                        playLabelOf(g)));
            }
        }
        return out;
    }

    /** PascalCase play name for the overlay, or empty when none committed. Read-only. */
    private static String playLabelOf(BattleGroup g) {
        PlayId play = g.currentPlay();
        if (play == null) return "";
        String[] parts = play.key.split("_");
        StringBuilder sb = new StringBuilder(play.key.length());
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1));
        }
        return sb.toString();
    }

    /**
     * Read-only: the Stage-4 assignment role for a driver, if any. Does not mutate play state.
     */
    @Nullable
    public static Assignment.Role assignmentRoleForDriver(int driverId) {
        for (Map<Integer, BattleGroup> levelGroups : GROUPS_BY_LEVEL.values()) {
            for (BattleGroup g : levelGroups.values()) {
                Roles roles = g.currentRoles();
                if (roles == null) continue;
                for (Assignment a : roles.assignments) {
                    if (a.unitId == driverId) return a.role;
                }
            }
        }
        return null;
    }

    /**
     * Plain copy of {@link BattleField} fields for the map packet. Flank <i>positions</i> are
     * decided by the tracker when packaging — not here.
     */
    public record BattleFieldDebug(
            int groupId, ResourceKey<Level> dimension,
            double friendlyX, double friendlyZ,
            double enemyX, double enemyZ,
            double axisX, double axisZ,
            boolean openFlankLeft, boolean openFlankRight,
            String playLabel
    ) {}

    private static void scanLevel(ServerLevel level, GroupParams params, int maxUnits, double engagement,
                                  int nowTick) {
        ResourceKey<Level> dim = level.dimension();
        Map<Integer, BattleGroup> levelGroups = GROUPS_BY_LEVEL.computeIfAbsent(dim, d -> new HashMap<>());

        List<Candidate> drivers = collectDrivers(level);
        drivers.sort(Comparator.comparingInt(c -> c.unitId));
        if (drivers.size() > maxUnits) {
            drivers = new ArrayList<>(drivers.subList(0, maxUnits));
        }

        Map<Integer, Candidate> byId = new HashMap<>(drivers.size() * 2);
        for (Candidate c : drivers) byId.put(c.unitId, c);

        double engagementSq = engagement * engagement;
        List<UnitPos> contested = new ArrayList<>();
        Set<Integer> contestedIds = new HashSet<>();
        for (Candidate c : drivers) {
            if (hasOpposingNearby(level, c, engagementSq)) {
                contested.add(new UnitPos(c.unitId, c.faction.ordinal(), c.x, c.z));
                contestedIds.add(c.unitId);
            }
        }

        // Keep members of a still-contested group in the candidate set even if they personally
        // drifted out of engagement — sticky through a lull on one flank.
        List<ExistingGroup> existing = new ArrayList<>();
        List<Integer> dissolveNoBattle = new ArrayList<>();
        for (BattleGroup g : levelGroups.values()) {
            if (!centroidHasOpposing(level, g, engagementSq)) {
                dissolveNoBattle.add(g.groupId());
                continue;
            }
            existing.add(g.toExisting());
            for (int memberId : g.memberIds()) {
                if (contestedIds.contains(memberId)) continue;
                Candidate c = byId.get(memberId);
                if (c == null || c.faction.ordinal() != g.faction()) continue;
                contested.add(new UnitPos(c.unitId, c.faction.ordinal(), c.x, c.z));
                contestedIds.add(c.unitId);
            }
        }
        for (int id : dissolveNoBattle) {
            if (levelGroups.remove(id) != null) {
                LOGGER.debug("[sewv-command] dissolve group {} (no engagement)", id);
            }
        }

        if (contested.isEmpty()) {
            // Nothing contested and no existing groups left for this level.
            return;
        }

        // Refresh existing list after dissolves.
        existing.clear();
        for (BattleGroup g : levelGroups.values()) {
            existing.add(g.toExisting());
        }

        List<AssignedGroup> assigned = Grouping.groupAssignments(
                contested, existing, params, () -> nextGroupId++);

        applyAssignments(levelGroups, assigned, existing);
        rebuildBattleFields(level, levelGroups, engagement);
        electCommanders(level, levelGroups, params);
        selectPlays(level, levelGroups, nowTick);
    }

    private static void selectPlays(ServerLevel level, Map<Integer, BattleGroup> levelGroups, int nowTick) {
        int minTicks;
        double margin;
        try {
            minTicks = SewvConfig.MIN_PLAY_TICKS.get();
            margin = SewvConfig.PLAY_SWITCH_MARGIN.get();
        } catch (Throwable ignored) {
            return;
        }
        UtilityWeights weights = UtilityWeights.active();
        for (BattleGroup group : levelGroups.values()) {
            try {
                selectPlayOne(level, group, nowTick, minTicks, margin, weights);
            } catch (Throwable t) {
                LOGGER.debug("[sewv-command] play select failed group {}: {}",
                        group.groupId(), t.toString());
            }
        }
    }

    private static void selectPlayOne(ServerLevel level, BattleGroup group, int nowTick,
                                      int minTicks, double margin, UtilityWeights weights) {
        if (!group.battleField().populated) {
            group.clearPlay();
            return;
        }
        int[] ids = group.memberIds();
        double[] xs = new double[ids.length];
        double[] zs = new double[ids.length];
        for (int i = 0; i < ids.length; i++) {
            var e = level.getEntity(ids[i]);
            if (e != null) {
                xs[i] = e.getX();
                zs[i] = e.getZ();
            } else {
                xs[i] = group.centroidX();
                zs[i] = group.centroidZ();
            }
        }
        GroupSnapshot snap = new GroupSnapshot(ids, xs, zs);
        PlaySelection.Result result = PlaySelection.select(
                group.battleField(), snap,
                group.currentPlay(), group.playStartedTick(), nowTick,
                group.currentRoles(),
                minTicks, margin, weights);
        Roles roles = withFocusFire(level, group, result.roles());
        if (result.switched() || group.currentPlay() == null
                || group.currentPlay() != result.play()) {
            LOGGER.debug("[sewv-command] play group={} {} play={}",
                    group.groupId(), result.reason(), result.play().key);
            group.commitPlay(result.play(), roles, nowTick);
        } else {
            // Keep start tick; refresh roles for moving geometry.
            long started = group.playStartedTick() == Long.MIN_VALUE ? nowTick : group.playStartedTick();
            group.commitPlay(result.play(), roles, started);
        }
    }

    /**
     * Soft focus-fire: stamp every role with the nearest opposing unit to the enemy centroid.
     * Does not hard-set {@code unit.setTarget} — only biases acquisition when the id is still live.
     */
    private static Roles withFocusFire(ServerLevel level, BattleGroup group, Roles roles) {
        if (roles == null || roles.size() == 0) return roles;
        Integer focus = nearestOpposingId(level, group);
        if (focus == null) return roles;
        Assignment[] out = new Assignment[roles.assignments.length];
        for (int i = 0; i < roles.assignments.length; i++) {
            Assignment a = roles.assignments[i];
            out[i] = new Assignment(a.unitId, a.role, focus, a.flankSide, a.destX, a.destZ);
        }
        return new Roles(out);
    }

    @Nullable
    private static Integer nearestOpposingId(ServerLevel level, BattleGroup group) {
        BattleField bf = group.battleField();
        if (!bf.populated) return null;
        CrewFacts.Faction ours = CrewFacts.Faction.byId(group.faction());
        double cx = bf.enemyCentroidX;
        double cz = bf.enemyCentroidZ;
        double best = Double.POSITIVE_INFINITY;
        Integer bestId = null;
        double r = 96.0;
        try {
            r = SewvConfig.COMMAND_ENGAGEMENT_RADIUS.get();
        } catch (Throwable ignored) {
        }
        double y = 64.0;
        for (int memberId : group.memberIds()) {
            var e = level.getEntity(memberId);
            if (e != null) {
                y = e.getY();
                break;
            }
        }
        AABB box = new AABB(cx - r, y - 32, cz - r, cx + r, y + 32, cz + r);
        for (AbstractUnit other : level.getEntities(EntityTypeTest.forClass(AbstractUnit.class), box, e -> true)) {
            CrewFacts.Faction f = CrewFacts.factionOfCrew(other);
            if (f == null || f == ours) continue;
            double dx = other.getX() - cx;
            double dz = other.getZ() - cz;
            double d = dx * dx + dz * dz;
            if (d < best || (d == best && (bestId == null || other.getId() < bestId))) {
                best = d;
                bestId = other.getId();
            }
        }
        return bestId;
    }

    /**
     * Influence + BattleField for every live group. Groups already passed the battle gate;
     * a group with no opposing samples this tick still clears rather than keeping a stale map.
     */
    private static void rebuildBattleFields(ServerLevel level, Map<Integer, BattleGroup> levelGroups,
                                            double engagement) {
        double cell;
        int maxCells;
        try {
            cell = SewvConfig.INFLUENCE_CELL_SIZE.get();
            maxCells = SewvConfig.INFLUENCE_MAX_CELLS.get();
        } catch (Throwable ignored) {
            return;
        }
        double margin = Math.max(cell * 2.0, engagement * 0.25);

        for (BattleGroup group : levelGroups.values()) {
            try {
                List<UnitPos> samples = collectInfluenceSamples(level, group, engagement);
                if (samples.isEmpty()) {
                    group.battleField().clear();
                    group.clearPlay();
                    continue;
                }
                // Only build when at least one opposing sample is present — mirrors the gate.
                boolean hasEnemy = false;
                for (UnitPos u : samples) {
                    if (u.faction != group.faction()) {
                        hasEnemy = true;
                        break;
                    }
                }
                if (!hasEnemy) {
                    group.battleField().clear();
                    group.clearPlay();
                    continue;
                }
                group.influenceMap().rebuildAndDerive(
                        group.battleField(), samples, group.faction(), cell, maxCells, margin);
            } catch (Throwable t) {
                group.battleField().clear();
                LOGGER.debug("[sewv-command] influence rebuild failed group {}: {}",
                        group.groupId(), t.toString());
            }
        }
    }

    /**
     * Friendly drivers in the group plus opposing units within engagement of the group centroid
     * (or any member). Plain {@link UnitPos} — no vehicle types leak into the map.
     */
    private static List<UnitPos> collectInfluenceSamples(ServerLevel level, BattleGroup group,
                                                         double engagement) {
        List<UnitPos> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int memberId : group.memberIds()) {
            var e = level.getEntity(memberId);
            if (e == null) continue;
            out.add(new UnitPos(memberId, group.faction(), e.getX(), e.getZ()));
            seen.add(memberId);
        }
        if (out.isEmpty()) return out;

        double r = engagement;
        double y = 64;
        for (int memberId : group.memberIds()) {
            var e = level.getEntity(memberId);
            if (e != null) {
                y = e.getY();
                break;
            }
        }
        AABB box = new AABB(group.centroidX() - r, y - 32, group.centroidZ() - r,
                group.centroidX() + r, y + 32, group.centroidZ() + r);
        CrewFacts.Faction ours = CrewFacts.Faction.byId(group.faction());
        double rSq = r * r;
        for (AbstractUnit other : level.getEntities(EntityTypeTest.forClass(AbstractUnit.class), box, e -> true)) {
            if (seen.contains(other.getId())) continue;
            CrewFacts.Faction f = CrewFacts.factionOfCrew(other);
            if (f == null || f == ours) continue;
            double dx = other.getX() - group.centroidX();
            double dz = other.getZ() - group.centroidZ();
            if (dx * dx + dz * dz > rSq) continue;
            out.add(new UnitPos(other.getId(), f.ordinal(), other.getX(), other.getZ()));
            seen.add(other.getId());
        }
        return out;
    }

    private static void electCommanders(ServerLevel level, Map<Integer, BattleGroup> levelGroups,
                                        GroupParams params) {
        double margin;
        int quorum;
        try {
            margin = SewvConfig.COMMAND_MARGIN.get();
            quorum = SewvConfig.COMMAND_GROUP_MIN_SIZE.get();
        } catch (Throwable ignored) {
            return;
        }
        UtilityWeights weights = UtilityWeights.active();
        double maxRadius = params.maxRadius();

        for (BattleGroup group : levelGroups.values()) {
            try {
                electOne(level, group, margin, quorum, maxRadius, weights);
            } catch (Throwable t) {
                LOGGER.debug("[sewv-command] election failed for group {}: {}",
                        group.groupId(), t.toString());
            }
        }
    }

    private static void electOne(ServerLevel level, BattleGroup group, double margin, int quorum,
                                 double maxRadius, UtilityWeights weights) {
        List<Election.Candidate> members = new ArrayList<>();
        Integer designatedNetId = resolveDesignation(level, group);

        for (int memberId : group.memberIds()) {
            var entity = level.getEntity(memberId);
            double x = group.centroidX();
            double z = group.centroidZ();
            if (entity != null) {
                x = entity.getX();
                z = entity.getZ();
            }
            Facts facts = Facts.of(memberId);
            boolean ready = facts != null && facts.ready();
            double fitness = 0.0;
            if (ready) {
                fitness = CommanderFitness.score(facts, x, z,
                        group.fitnessCentroidX(), group.fitnessCentroidZ(), maxRadius, weights);
            }
            members.add(new Election.Candidate(memberId, ready, fitness));
        }

        Integer incumbent = group.hasCommander() ? group.commanderId() : null;
        Integer elected = Election.electCommander(members, incumbent, margin, designatedNetId, quorum);
        if (elected == null) {
            LOGGER.debug("[sewv-command] election deferred: no ready Facts group={}", group.groupId());
            return;
        }
        if (!group.hasCommander() || group.commanderId() != elected) {
            int old = group.hasCommander() ? group.commanderId() : -1;
            String reason = designatedNetId != null && designatedNetId.equals(elected) ? "player"
                    : (incumbent == null ? "no-incumbent"
                    : (incumbent == elected ? "kept" : "beaten"));
            double oldFit = fitnessOf(members, incumbent);
            double newFit = fitnessOf(members, elected);
            LOGGER.debug("[sewv-command] command change group={} old={}({}) new={}({}) reason={}",
                    group.groupId(), old, oldFit, elected, newFit, reason);
            group.setCommanderId(elected);
        }
    }

    private static double fitnessOf(List<Election.Candidate> members, @Nullable Integer id) {
        if (id == null) return Double.NaN;
        for (Election.Candidate c : members) {
            if (c.id == id) return c.ready ? c.fitness : Double.NaN;
        }
        return Double.NaN;
    }

    /**
     * TODO(command-player-designation): resolve the stub UUID to a live in-group network id.
     */
    @Nullable
    private static Integer resolveDesignation(ServerLevel level, BattleGroup group) {
        UUID designated = group.playerDesignatedCommander();
        if (designated == null) return null;
        for (int memberId : group.memberIds()) {
            var e = level.getEntity(memberId);
            if (e != null && designated.equals(e.getUUID())) return memberId;
        }
        return null; // dead or left group — fall through to auto
    }

    private static void applyAssignments(Map<Integer, BattleGroup> levelGroups,
                                         List<AssignedGroup> assigned,
                                         List<ExistingGroup> previous) {
        Set<Integer> previousIds = new HashSet<>();
        Map<Integer, Set<Integer>> previousMembers = new HashMap<>();
        for (ExistingGroup eg : previous) {
            previousIds.add(eg.groupId);
            Set<Integer> set = new HashSet<>();
            for (int id : eg.memberIds) set.add(id);
            previousMembers.put(eg.groupId, set);
        }

        Set<Integer> liveIds = new HashSet<>();
        for (AssignedGroup ag : assigned) {
            liveIds.add(ag.groupId);
            BattleGroup existing = levelGroups.get(ag.groupId);
            if (existing == null) {
                BattleGroup created = new BattleGroup(ag.groupId, ag.faction, ag.memberIds,
                        ag.centroidX, ag.centroidZ);
                levelGroups.put(ag.groupId, created);
                LOGGER.debug("[sewv-command] form group {} faction={} members={}",
                        ag.groupId, ag.faction, Arrays.toString(ag.memberIds));
            } else {
                Set<Integer> before = previousMembers.getOrDefault(ag.groupId, Set.of());
                Set<Integer> after = new HashSet<>();
                for (int id : ag.memberIds) after.add(id);
                existing.apply(ag);
                if (!before.equals(after)) {
                    LOGGER.debug("[sewv-command] membership group {} -> {}",
                            ag.groupId, Arrays.toString(ag.memberIds));
                }
            }
        }

        for (int id : previousIds) {
            if (!liveIds.contains(id) && levelGroups.remove(id) != null) {
                LOGGER.debug("[sewv-command] dissolve group {} (below min size / split)", id);
            }
        }
    }

    private static List<Candidate> collectDrivers(ServerLevel level) {
        List<Candidate> out = new ArrayList<>();
        try {
            for (VehicleEntity hull : level.getEntities(EntityTypeTest.forClass(VehicleEntity.class), h -> true)) {
                AbstractUnit driver = CommandEligibility.eligibleDriver(hull);
                if (driver == null) continue;
                CrewFacts.Faction faction = CrewFacts.factionOfCrew(driver);
                if (faction == null) continue;
                out.add(new Candidate(driver.getId(), faction, hull.getX(), hull.getZ(), hull));
            }
        } catch (Throwable t) {
            LOGGER.debug("[sewv-command] driver scan failed: {}", t.toString());
        }
        return out;
    }

    private static boolean hasOpposingNearby(ServerLevel level, Candidate c, double radiusSq) {
        try {
            double r = Math.sqrt(radiusSq);
            AABB box = new AABB(c.x - r, c.hull.getY() - 32, c.z - r,
                    c.x + r, c.hull.getY() + 32, c.z + r);
            for (AbstractUnit other : level.getEntities(EntityTypeTest.forClass(AbstractUnit.class), box, e -> true)) {
                if (other.getId() == c.unitId) continue;
                CrewFacts.Faction f = CrewFacts.factionOfCrew(other);
                if (f == null || f == c.faction) continue;
                double dx = other.getX() - c.x;
                double dz = other.getZ() - c.z;
                if (dx * dx + dz * dz <= radiusSq) return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    private static boolean centroidHasOpposing(ServerLevel level, BattleGroup g, double radiusSq) {
        try {
            double r = Math.sqrt(radiusSq);
            double y = 64;
            for (int memberId : g.memberIds()) {
                var e = level.getEntity(memberId);
                if (e != null) {
                    y = e.getY();
                    break;
                }
            }
            AABB box = new AABB(g.centroidX() - r, y - 32, g.centroidZ() - r,
                    g.centroidX() + r, y + 32, g.centroidZ() + r);
            CrewFacts.Faction ours = CrewFacts.Faction.byId(g.faction());
            for (AbstractUnit other : level.getEntities(EntityTypeTest.forClass(AbstractUnit.class), box, e -> true)) {
                CrewFacts.Faction f = CrewFacts.factionOfCrew(other);
                if (f == null || f == ours) continue;
                double dx = other.getX() - g.centroidX();
                double dz = other.getZ() - g.centroidZ();
                if (dx * dx + dz * dz <= radiusSq) return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    private record Candidate(int unitId, CrewFacts.Faction faction, double x, double z, VehicleEntity hull) {}
}
