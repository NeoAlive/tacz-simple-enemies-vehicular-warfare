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
 * and runs the pure {@link Grouping} core.
 *
 * <p>Stage 1 only — no election, influence, or plays yet. Groups are keyed per dimension so two
 * worlds cannot share a sticky identity.
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
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        nextScan = Integer.MIN_VALUE;
        GROUPS_BY_LEVEL.clear();
        CommandEligibility.clearCache();
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
            scanLevel(level, params, maxUnits, engagement);
        }
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

    private static void scanLevel(ServerLevel level, GroupParams params, int maxUnits, double engagement) {
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
        electCommanders(level, levelGroups, params);
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
                        group.centroidX(), group.centroidZ(), maxRadius, weights);
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
