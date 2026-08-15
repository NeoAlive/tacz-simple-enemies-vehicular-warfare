package com.neoalive.tacz_sewv.entity.ai.command.platoon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.entity.ai.command.CommandEligibility;
import com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity;

/**
 * Server-side owner of Platoons — a sticky, non-combat-gated identity independent of
 * {@link com.neoalive.tacz_sewv.entity.ai.command.CommandCoordinator}'s battle groups (which
 * dissolve without an active engagement and are scoped to vehicle drivers only).
 *
 * <p><b>A platoon only ever forms around a live {@link PmcCommanderEntity}, one per commander.</b>
 * Each scan walks every not-yet-leading commander (sorted by id, so results are deterministic) and
 * claims the nearest same-owner, not-yet-claimed candidates within the cohesion radius. Ordinary
 * units with no commander in range never cluster into a leaderless platoon among themselves.
 *
 * <p><b>The size cap's unit changes with the commander's own mount state.</b> A commander on foot
 * caps at {@code PLATOON_MAX_SIZE} individual infantry. A commander seated in an eligible ground
 * vehicle (any seat, not just the driver's) caps at {@code PLATOON_MAX_SIZE} <i>vehicles</i>
 * instead — every claimed vehicle's whole PMC crew joins, which is normally more people than the
 * on-foot cap would allow. If that commander later dismounts, the scope reverts to the per-person
 * cap and {@link #enforceDismountedCap} sheds whatever no longer fits, closest members kept first.
 *
 * <p>Runs on the same {@code utilityRefreshIntervalTicks} cadence as
 * {@code CommandCoordinator}, but as its own event-bus listener — platoons must also cover
 * dismounted infantry, which {@link CommandEligibility} structurally cannot see (driver-only).
 */
public final class PlatoonRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static int nextScan = Integer.MIN_VALUE;
    private static int nextGroupId = 1;

    private static final Map<ResourceKey<Level>, Map<Integer, Platoon>> GROUPS_BY_LEVEL = new HashMap<>();
    /** unitId -> owning platoon, rebuilt every scan alongside the groups themselves. */
    private static final Map<ResourceKey<Level>, Map<Integer, Platoon>> MEMBER_INDEX = new HashMap<>();

    private PlatoonRegistry() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        nextScan = Integer.MIN_VALUE;
        nextGroupId = 1;
        GROUPS_BY_LEVEL.clear();
        MEMBER_INDEX.clear();
        CommanderOrderDispatch.clearDeadlines();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        nextScan = Integer.MIN_VALUE;
        GROUPS_BY_LEVEL.clear();
        MEMBER_INDEX.clear();
        CommanderOrderDispatch.clearDeadlines();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        int now = event.getServer().getTickCount();
        if (now < nextScan) return;
        int interval;
        double cohesionRadius;
        int maxSize;
        int minSize;
        try {
            interval = SewvConfig.UTILITY_REFRESH_INTERVAL_TICKS.get();
            cohesionRadius = SewvConfig.PLATOON_COHESION_RADIUS.get();
            maxSize = SewvConfig.PLATOON_MAX_SIZE.get();
            minSize = SewvConfig.PLATOON_MIN_SIZE.get();
        } catch (Throwable ignored) {
            return;
        }
        nextScan = now + interval;

        for (ServerLevel level : event.getServer().getAllLevels()) {
            try {
                scanLevel(level, cohesionRadius, maxSize, minSize);
                CommanderOrderDispatch.expireStale(level);
            } catch (Throwable t) {
                LOGGER.debug("[sewv-platoon] scan failed for {}: {}", level.dimension().location(), t.toString());
            }
        }
    }

    /** Owning platoon for a live member, or null. O(1) via the per-scan reverse index. */
    @Nullable
    public static Platoon platoonOf(ServerLevel level, int unitId) {
        Map<Integer, Platoon> index = MEMBER_INDEX.get(level.dimension());
        return index == null ? null : index.get(unitId);
    }

    /** One dismounted PMC candidate for the on-foot formation pass. */
    private record Candidate(int id, UUID uuid, UUID owner, double x, double z, boolean commander) {}

    /**
     * One crewed ground vehicle for the mounted formation pass — a candidate in its own right.
     * {@code commanderUuid} is the persistent entity UUID of the crewed Commander, or {@code null}
     * when {@code hasCommander} is false — carried here rather than re-scanned later so the color
     * seed ({@link Platoon}'s constructor) always has it at formation time.
     */
    private record VehicleCandidate(int hullId, UUID owner, double x, double z,
                                     boolean hasCommander, UUID commanderUuid, int[] crewIds) {}

    private static void scanLevel(ServerLevel level, double cohesionRadius, int maxSize, int minSize) {
        ResourceKey<Level> dim = level.dimension();
        Map<Integer, Platoon> levelGroups = GROUPS_BY_LEVEL.computeIfAbsent(dim, d -> new HashMap<>());

        pruneExisting(level, levelGroups, minSize);
        enforceDismountedCap(level, levelGroups, maxSize);

        Set<Integer> claimed = new HashSet<>();
        for (Platoon p : levelGroups.values()) {
            for (int id : p.memberIds()) claimed.add(id);
        }

        // Growing an already-formed platoon is preferred over seeding a brand-new one out of the
        // same nearby units — same shape as Grouping's own join-before-form ordering.
        List<Candidate> infantryPool = collectInfantry(level, claimed);
        joinExistingInfantryPlatoons(level, levelGroups, infantryPool, cohesionRadius, maxSize, claimed);
        formInfantryAroundCommanders(level, levelGroups, infantryPool, cohesionRadius, maxSize, minSize, claimed);

        List<VehicleCandidate> vehiclePool = collectVehicles(level, claimed);
        joinExistingVehiclePlatoons(level, levelGroups, vehiclePool, cohesionRadius, maxSize, claimed);
        formVehiclesAroundCommanders(level, levelGroups, vehiclePool, cohesionRadius, maxSize, minSize, claimed);

        resolveCommanders(level, levelGroups);
        rebuildMemberIndex(dim, levelGroups);
    }

    /**
     * Any ungrouped, same-owner, on-foot PMC unit within the cohesion radius of an INFANTRY
     * platoon's centroid joins automatically, provided that platoon has not reached
     * {@code maxSize}. Commanders never auto-join — each seeds its own (see the class doc).
     */
    private static void joinExistingInfantryPlatoons(ServerLevel level, Map<Integer, Platoon> levelGroups,
                                                      List<Candidate> pool, double radius, int maxSize,
                                                      Set<Integer> claimed) {
        List<Platoon> platoons = new ArrayList<>();
        for (Platoon p : levelGroups.values()) {
            if (p.type() == Platoon.Type.INFANTRY) platoons.add(p);
        }
        if (platoons.isEmpty()) return;
        platoons.sort(Comparator.comparingInt(Platoon::groupId));
        double radiusSq = radius * radius;

        for (Candidate c : pool) {
            if (c.commander() || claimed.contains(c.id())) continue;

            Platoon best = null;
            double bestDist = Double.MAX_VALUE;
            for (Platoon p : platoons) {
                if (p.size() >= maxSize || !p.owner().equals(c.owner())) continue;
                double dx = c.x() - p.centroidX();
                double dz = c.z() - p.centroidZ();
                double d = dx * dx + dz * dz;
                if (d > radiusSq || d >= bestDist) continue;
                best = p;
                bestDist = d;
            }
            if (best != null) {
                best.addMember(c.id());
                claimed.add(c.id());
            }
        }
    }

    /**
     * Any ungrouped, same-owner crewed ground vehicle within the cohesion radius of a
     * GROUND_VEHICLE platoon's centroid joins automatically — whole crew, same as formation —
     * provided that platoon has not reached {@code maxVehicles} <b>distinct vehicles</b>.
     */
    private static void joinExistingVehiclePlatoons(ServerLevel level, Map<Integer, Platoon> levelGroups,
                                                     List<VehicleCandidate> pool, double radius, int maxVehicles,
                                                     Set<Integer> claimed) {
        List<Platoon> platoons = new ArrayList<>();
        for (Platoon p : levelGroups.values()) {
            if (p.type() == Platoon.Type.GROUND_VEHICLE) platoons.add(p);
        }
        if (platoons.isEmpty()) return;
        platoons.sort(Comparator.comparingInt(Platoon::groupId));
        double radiusSq = radius * radius;

        for (VehicleCandidate v : pool) {
            if (v.hasCommander() || anyClaimed(v, claimed)) continue;

            Platoon best = null;
            double bestDist = Double.MAX_VALUE;
            for (Platoon p : platoons) {
                if (!p.owner().equals(v.owner()) || distinctVehicleCount(level, p) >= maxVehicles) continue;
                double dx = v.x() - p.centroidX();
                double dz = v.z() - p.centroidZ();
                double d = dx * dx + dz * dz;
                if (d > radiusSq || d >= bestDist) continue;
                best = p;
                bestDist = d;
            }
            if (best != null) {
                for (int id : v.crewIds()) {
                    best.addMember(id);
                    claimed.add(id);
                }
            }
        }
    }

    private static int distinctVehicleCount(ServerLevel level, Platoon p) {
        Set<Integer> hulls = new HashSet<>();
        for (int id : p.memberIds()) {
            Entity e = level.getEntity(id);
            if (e != null && e.getVehicle() instanceof VehicleEntity hull) {
                hulls.add(hull.getId());
            }
        }
        return hulls.size();
    }

    /**
     * Exactly one new platoon per not-yet-leading on-foot commander, filled with its nearest
     * dismounted same-owner candidates — capped by headcount. Commanders never fill in as a member
     * of another commander's platoon; each always gets its own.
     */
    private static void formInfantryAroundCommanders(ServerLevel level, Map<Integer, Platoon> levelGroups,
                                                      List<Candidate> pool, double radius, int maxSize,
                                                      int minSize, Set<Integer> claimed) {
        List<Candidate> commanders = new ArrayList<>();
        for (Candidate c : pool) {
            if (c.commander()) commanders.add(c);
        }
        commanders.sort(Comparator.comparingInt(Candidate::id));
        double radiusSq = radius * radius;

        for (Candidate cmd : commanders) {
            if (claimed.contains(cmd.id())) continue;

            List<Candidate> nearby = new ArrayList<>();
            for (Candidate c : pool) {
                if (c.commander() || c.id() == cmd.id() || claimed.contains(c.id())) continue;
                if (!c.owner().equals(cmd.owner())) continue;
                double dx = c.x() - cmd.x();
                double dz = c.z() - cmd.z();
                if (dx * dx + dz * dz > radiusSq) continue;
                nearby.add(c);
            }
            nearby.sort(Comparator.comparingDouble(c -> distSqCandidate(c, cmd)));

            List<Candidate> selected = new ArrayList<>();
            selected.add(cmd);
            for (Candidate c : nearby) {
                if (selected.size() >= maxSize) break;
                selected.add(c);
            }
            if (selected.size() < minSize) continue; // not enough nearby yet — retry next scan

            int[] ids = new int[selected.size()];
            double cx = 0, cz = 0;
            for (int i = 0; i < selected.size(); i++) {
                Candidate c = selected.get(i);
                ids[i] = c.id();
                cx += c.x();
                cz += c.z();
            }
            Arrays.sort(ids);
            int groupId = nextGroupId++;
            Platoon created = new Platoon(groupId, Platoon.Type.INFANTRY, cmd.owner(), cmd.uuid(),
                    ids, cx / ids.length, cz / ids.length);
            levelGroups.put(groupId, created);
            for (int id : ids) claimed.add(id);
            LOGGER.debug("[sewv-platoon] form {} type=INFANTRY commander={} members={}",
                    created.id(), cmd.id(), Arrays.toString(ids));
        }
    }

    /**
     * Exactly one new platoon per not-yet-leading mounted commander, filled with its nearest
     * same-owner crewed vehicles — capped by <b>vehicle</b> count, not headcount. Every claimed
     * vehicle contributes its whole PMC crew, so total membership is normally well past the
     * on-foot cap; {@link #enforceDismountedCap} is what reins that back in if the commander
     * later gets out.
     */
    private static void formVehiclesAroundCommanders(ServerLevel level, Map<Integer, Platoon> levelGroups,
                                                      List<VehicleCandidate> pool, double radius, int maxVehicles,
                                                      int minSize, Set<Integer> claimed) {
        List<VehicleCandidate> commanderVehicles = new ArrayList<>();
        for (VehicleCandidate v : pool) {
            if (v.hasCommander()) commanderVehicles.add(v);
        }
        commanderVehicles.sort(Comparator.comparingInt(VehicleCandidate::hullId));
        double radiusSq = radius * radius;

        for (VehicleCandidate cmdVeh : commanderVehicles) {
            if (anyClaimed(cmdVeh, claimed)) continue;

            List<VehicleCandidate> nearby = new ArrayList<>();
            for (VehicleCandidate v : pool) {
                if (v.hasCommander() || v.hullId() == cmdVeh.hullId()) continue;
                if (!v.owner().equals(cmdVeh.owner()) || anyClaimed(v, claimed)) continue;
                double dx = v.x() - cmdVeh.x();
                double dz = v.z() - cmdVeh.z();
                if (dx * dx + dz * dz > radiusSq) continue;
                nearby.add(v);
            }
            nearby.sort(Comparator.comparingDouble(v -> distSqVehicle(v, cmdVeh)));

            List<VehicleCandidate> selected = new ArrayList<>();
            selected.add(cmdVeh);
            for (VehicleCandidate v : nearby) {
                if (selected.size() >= maxVehicles) break;
                selected.add(v);
            }

            List<Integer> allCrew = new ArrayList<>();
            double cx = 0, cz = 0;
            for (VehicleCandidate v : selected) {
                for (int id : v.crewIds()) allCrew.add(id);
                cx += v.x();
                cz += v.z();
            }
            if (allCrew.size() < minSize) continue; // not enough people yet — retry next scan

            int[] ids = allCrew.stream().mapToInt(Integer::intValue).toArray();
            Arrays.sort(ids);
            int groupId = nextGroupId++;
            Platoon created = new Platoon(groupId, Platoon.Type.GROUND_VEHICLE, cmdVeh.owner(), cmdVeh.commanderUuid(),
                    ids, cx / selected.size(), cz / selected.size());
            levelGroups.put(groupId, created);
            for (int id : ids) claimed.add(id);
            LOGGER.debug("[sewv-platoon] form {} type=GROUND_VEHICLE commander_hull={} vehicles={} members={}",
                    created.id(), cmdVeh.hullId(), selected.size(), Arrays.toString(ids));
        }
    }

    private static boolean anyClaimed(VehicleCandidate v, Set<Integer> claimed) {
        for (int id : v.crewIds()) {
            if (claimed.contains(id)) return true;
        }
        return false;
    }

    private static double distSqCandidate(Candidate c, Candidate origin) {
        double dx = c.x() - origin.x();
        double dz = c.z() - origin.z();
        return dx * dx + dz * dz;
    }

    private static double distSqVehicle(VehicleCandidate v, VehicleCandidate origin) {
        double dx = v.x() - origin.x();
        double dz = v.z() - origin.z();
        return dx * dx + dz * dz;
    }

    /**
     * Drop only members confirmed dead/unloaded — sticky otherwise, no distance eviction. The
     * whole platoon force-disbands when its commander is one of those (not just demoted), or when
     * the survivors drop below {@code minSize}.
     */
    private static void pruneExisting(ServerLevel level, Map<Integer, Platoon> levelGroups, int minSize) {
        List<Integer> disband = new ArrayList<>();
        for (Platoon p : levelGroups.values()) {
            if (p.hasCommander()) {
                Entity c = level.getEntity(p.commanderId());
                if (!(c instanceof PmcCommanderEntity) || !c.isAlive()) {
                    disband.add(p.groupId());
                    continue;
                }
            }

            List<Integer> alive = new ArrayList<>();
            double sx = 0, sz = 0;
            for (int id : p.memberIds()) {
                Entity e = level.getEntity(id);
                if (e == null || !e.isAlive()) continue;
                alive.add(id);
                sx += e.getX();
                sz += e.getZ();
            }
            if (alive.size() < minSize) {
                disband.add(p.groupId());
                continue;
            }
            int[] aliveIds = alive.stream().mapToInt(Integer::intValue).toArray();
            Arrays.sort(aliveIds);
            p.applyMembers(aliveIds, sx / aliveIds.length, sz / aliveIds.length);
        }
        for (int groupId : disband) {
            Platoon removed = removeByGroupId(levelGroups, groupId);
            if (removed != null) {
                LOGGER.debug("[sewv-platoon] disband {} (commander lost / below min size)", removed.id());
            }
        }
    }

    /**
     * A vehicle-scoped platoon (formed while its commander was mounted, so its roster can be well
     * past the per-person cap) reverts to the per-person cap the moment that commander is no
     * longer seated in an eligible ground vehicle — "seated" means any seat, not just the driver's,
     * matching how {@link #collectVehicles} decides {@code hasCommander}. Trims to {@code maxSize}
     * members, commander plus its nearest survivors by distance; the rest are simply dropped —
     * freed to be picked up as fresh candidates by this same scan's formation passes.
     */
    private static void enforceDismountedCap(ServerLevel level, Map<Integer, Platoon> levelGroups, int maxSize) {
        for (Platoon p : levelGroups.values()) {
            if (p.type() != Platoon.Type.GROUND_VEHICLE || !p.hasCommander() || p.size() <= maxSize) continue;
            Entity commander = level.getEntity(p.commanderId());
            if (commander == null) continue;
            if (commander.getVehicle() instanceof VehicleEntity hull && CommandEligibility.eligibleDriver(hull) != null) {
                continue; // still seated in an eligible hull — vehicle scope still applies
            }

            double cx = commander.getX();
            double cz = commander.getZ();
            List<Integer> others = new ArrayList<>();
            for (int id : p.memberIds()) {
                if (id != p.commanderId()) others.add(id);
            }
            others.sort(Comparator.comparingDouble(id -> distSqTo(level, id, cx, cz)));

            List<Integer> kept = new ArrayList<>();
            kept.add(p.commanderId());
            for (int id : others) {
                if (kept.size() >= maxSize) break;
                kept.add(id);
            }
            int[] keptIds = kept.stream().mapToInt(Integer::intValue).toArray();
            Arrays.sort(keptIds);

            double sx = 0, sz = 0;
            int n = 0;
            for (int id : keptIds) {
                Entity e = level.getEntity(id);
                if (e == null) continue;
                sx += e.getX();
                sz += e.getZ();
                n++;
            }
            p.applyMembers(keptIds, n > 0 ? sx / n : cx, n > 0 ? sz / n : cz);
            LOGGER.debug("[sewv-platoon] trim {} to {} members (commander dismounted)", p.id(), keptIds.length);
        }
    }

    private static double distSqTo(ServerLevel level, int unitId, double x, double z) {
        Entity e = level.getEntity(unitId);
        if (e == null) return Double.MAX_VALUE;
        double dx = e.getX() - x;
        double dz = e.getZ() - z;
        return dx * dx + dz * dz;
    }

    /** Remove one live member early ("Exit Platoon"); disbands the platoon if that drops it below minSize. */
    public static void exitPlatoon(ServerLevel level, int unitId) {
        Platoon platoon = platoonOf(level, unitId);
        if (platoon == null) return;
        Map<Integer, Platoon> levelGroups = GROUPS_BY_LEVEL.get(level.dimension());
        if (levelGroups == null) return;

        platoon.removeMember(unitId);
        int minSize;
        try {
            minSize = SewvConfig.PLATOON_MIN_SIZE.get();
        } catch (Throwable ignored) {
            minSize = 2;
        }
        if (platoon.size() < minSize) {
            removeByGroupId(levelGroups, platoon.groupId());
            LOGGER.debug("[sewv-platoon] disband {} (exit dropped below min size)", platoon.id());
        }
        rebuildMemberIndex(level.dimension(), levelGroups);
    }

    /** Outcome of a manual "Join Platoon" attempt — one per unit, so the TDT can report each. */
    public enum JoinResult { OK, ALREADY_IN, NOT_A_UNIT, NOT_OWNED, NO_PLATOON, MUST_BE_ON_FOOT, MUST_BE_MOUNTED, FULL }

    /**
     * Manual join: the player aimed at {@code commanderId} and asked {@code unitId} to join its
     * platoon. Same eligibility the automatic joiner uses (owner, on-foot/mounted match, cap), so
     * a unit that would have auto-joined anyway succeeds immediately rather than waiting a scan.
     */
    public static JoinResult joinPlatoon(ServerLevel level, int commanderId, int unitId) {
        Platoon platoon = platoonOf(level, commanderId);
        if (platoon == null || !platoon.hasCommander() || platoon.commanderId() != commanderId) {
            return JoinResult.NO_PLATOON;
        }
        if (platoon.contains(unitId)) return JoinResult.ALREADY_IN;

        Entity e = level.getEntity(unitId);
        if (!(e instanceof PmcUnitEntity pmc) || !pmc.isAlive()) return JoinResult.NOT_A_UNIT;
        if (!platoon.owner().equals(pmc.getOwnerUUID())) return JoinResult.NOT_OWNED;
        if (platoonOf(level, unitId) != null) return JoinResult.ALREADY_IN;

        int maxSize;
        try {
            maxSize = SewvConfig.PLATOON_MAX_SIZE.get();
        } catch (Throwable ignored) {
            maxSize = 4;
        }

        if (platoon.type() == Platoon.Type.INFANTRY) {
            if (pmc.getVehicle() != null) return JoinResult.MUST_BE_ON_FOOT;
            if (platoon.size() >= maxSize) return JoinResult.FULL;
            platoon.addMember(unitId);
        } else {
            if (!(pmc.getVehicle() instanceof VehicleEntity hull) || CommandEligibility.eligibleDriver(hull) == null) {
                return JoinResult.MUST_BE_MOUNTED;
            }
            Set<Integer> hulls = new HashSet<>();
            for (int id : platoon.memberIds()) {
                Entity m = level.getEntity(id);
                if (m != null && m.getVehicle() instanceof VehicleEntity h) hulls.add(h.getId());
            }
            if (!hulls.contains(hull.getId()) && hulls.size() >= maxSize) return JoinResult.FULL;
            for (Entity passenger : hull.getPassengers()) {
                if (passenger instanceof PmcUnitEntity crewPmc && platoon.owner().equals(crewPmc.getOwnerUUID())
                        && !platoon.contains(crewPmc.getId())) {
                    platoon.addMember(crewPmc.getId());
                }
            }
        }

        Map<Integer, Platoon> levelGroups = GROUPS_BY_LEVEL.get(level.dimension());
        if (levelGroups != null) rebuildMemberIndex(level.dimension(), levelGroups);
        return JoinResult.OK;
    }

    @Nullable
    private static Platoon removeByGroupId(Map<Integer, Platoon> levelGroups, int groupId) {
        return levelGroups.remove(groupId);
    }

    private static void resolveCommanders(ServerLevel level, Map<Integer, Platoon> levelGroups) {
        for (Platoon p : levelGroups.values()) {
            int commander = -1;
            for (int memberId : p.memberIds()) {
                if (level.getEntity(memberId) instanceof PmcCommanderEntity) {
                    commander = memberId;
                    break;
                }
            }
            p.setCommanderId(commander);
        }
    }

    private static void rebuildMemberIndex(ResourceKey<Level> dim, Map<Integer, Platoon> levelGroups) {
        Map<Integer, Platoon> index = new HashMap<>();
        for (Platoon p : levelGroups.values()) {
            for (int memberId : p.memberIds()) {
                index.put(memberId, p);
            }
        }
        MEMBER_INDEX.put(dim, index);
    }

    private static List<Candidate> collectInfantry(ServerLevel level, Set<Integer> claimed) {
        List<Candidate> out = new ArrayList<>();
        for (PmcUnitEntity unit : level.getEntities(EntityTypeTest.forClass(PmcUnitEntity.class), e -> true)) {
            if (unit.getVehicle() != null || !unit.isAlive()) continue;
            if (claimed.contains(unit.getId())) continue;
            UUID owner = unit.getOwnerUUID();
            if (owner == null) continue;
            out.add(new Candidate(unit.getId(), unit.getUUID(), owner, unit.getX(), unit.getZ(),
                    unit instanceof PmcCommanderEntity));
        }
        return out;
    }

    /**
     * One candidate per crewed ground-utility hull with an eligible driver — full crew list
     * (every PMC seat, not just the driver), and {@code hasCommander} true if the Commander is
     * riding it in <b>any</b> seat. Skips a hull if any of its own crew are already claimed by an
     * existing platoon, so a partially-claimed vehicle is never split across two.
     */
    private static List<VehicleCandidate> collectVehicles(ServerLevel level, Set<Integer> claimed) {
        List<VehicleCandidate> out = new ArrayList<>();
        for (VehicleEntity hull : level.getEntities(EntityTypeTest.forClass(VehicleEntity.class), h -> true)) {
            AbstractUnit driver = CommandEligibility.eligibleDriver(hull);
            if (driver == null || CrewFacts.factionOfCrew(driver) != CrewFacts.Faction.PMC) continue;
            UUID owner = CrewFacts.pmcOwner(hull);
            if (owner == null) continue;

            List<Integer> crew = new ArrayList<>();
            boolean hasCommander = false;
            UUID commanderUuid = null;
            boolean anyAlreadyClaimed = false;
            for (Entity passenger : hull.getPassengers()) {
                if (!(passenger instanceof PmcUnitEntity pmc)) continue;
                if (claimed.contains(pmc.getId())) anyAlreadyClaimed = true;
                crew.add(pmc.getId());
                if (pmc instanceof PmcCommanderEntity) {
                    hasCommander = true;
                    commanderUuid = pmc.getUUID();
                }
            }
            if (anyAlreadyClaimed || crew.isEmpty()) continue;

            out.add(new VehicleCandidate(hull.getId(), owner, hull.getX(), hull.getZ(), hasCommander,
                    commanderUuid, crew.stream().mapToInt(Integer::intValue).toArray()));
        }
        return out;
    }
}
