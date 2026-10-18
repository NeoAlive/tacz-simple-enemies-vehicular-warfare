package com.neoalive.tacz_sewv.invasion;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.bridge.ICaptureOrder;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.support.InfantrySquads;
import com.neoalive.tacz_sewv.invasion.force.BaseForce;
import com.neoalive.tacz_sewv.invasion.force.ForceKind;
import com.neoalive.tacz_sewv.spawn.InfantrySpawner;
import com.neoalive.tacz_sewv.spawn.TankSpawner;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.VehicleClassify;

/**
 * Fields what each placed {@link TeamBaseBlockEntity} configures ({@link BaseForce}): AI ground
 * hulls, helicopters, airborne planes and infantry, each topped up to its own count while the
 * match runs, plus each player's deployment ({@link InvasionDeploy}). Every fielded entity is
 * tagged ({@link InvasionTags}) with its team, base, {@link ForceKind} and whether it captures,
 * so the capture scan and the AI never need to look the base up again.
 */
public final class InvasionSpawn {

    /** Chunk radius around each online player searched for team_base BEs on start. */
    private static final int BASE_SCAN_CHUNKS = 24;
    /** {@link #findLoadedCapturables} answer is reused this long — AI re-picks objectives often. */
    private static final long CAPTURABLE_CACHE_TICKS = 20;

    private static final Map<ResourceKey<Level>, CachedScan> CAPTURABLES = new ConcurrentHashMap<>();

    private record CachedScan(long gameTime, List<CapturableBlockEntity> zones) {}

    private InvasionSpawn() {}

    /**
     * Fields every loaded team_base near players: AI forces of every kind, and a deployment offer
     * to each member of a player-owned base. Marks each base's {@code ownedTeam} as its
     * {@code assignedTeam}.
     *
     * @return summary counts for chat feedback
     */
    public static Result spawnAll(ServerLevel level) {
        List<TeamBaseBlockEntity> bases = findTeamBases(level);
        Map<ForceKind, Integer> fielded = new EnumMap<>(ForceKind.class);
        int playersDeploying = 0;
        int respawns = 0;

        for (TeamBaseBlockEntity base : bases) {
            String team = base.getAssignedTeam();
            if (team.isEmpty()) {
                SewvDiag.invasion("spawnSkip base={} — no assignedTeam", base.getBlockPos());
                continue;
            }

            // Implicit holder for capture rules / billboard.
            if (base.getOwnedTeam().isEmpty()) {
                base.setOwnedTeam(team);
            }

            if (base.isPlayerOwned()) {
                List<ServerPlayer> members = playersOnTeam(level, team);
                for (ServerPlayer player : members) {
                    InvasionDeploy.offer(level, base, player);
                    pinRespawn(player, level, base.getBlockPos().above());
                    playersDeploying++;
                    respawns++;
                }
                if (members.isEmpty()) {
                    SewvDiag.invasion("spawnWarn base={} team={} — player-owned, no online members",
                            base.getBlockPos(), team);
                }
            }

            for (ForceKind kind : ForceKind.VEHICLES) {
                fielded.merge(kind, spawnAiVehicles(level, base, kind, base.getForce().aiCount(kind)), Integer::sum);
            }
            fielded.merge(ForceKind.INFANTRY,
                    spawnAiInfantry(level, base, InvasionSession.infantryTarget(level, base)), Integer::sum);
        }

        int aiVehicles = 0;
        for (ForceKind kind : ForceKind.VEHICLES) aiVehicles += fielded.getOrDefault(kind, 0);
        SewvDiag.invasion("spawnDone bases={} fielded={} playersDeploying={} respawnsPinned={}",
                bases.size(), fielded, playersDeploying, respawns);
        CaptureOrderSupport.beginAll(level);
        verifySpawn(level, fielded);
        return new Result(bases.size(), aiVehicles, fielded.getOrDefault(ForceKind.INFANTRY, 0),
                playersDeploying, respawns);
    }

    /** Log-backed post-spawn check: tagged AI per kind matches what was fielded (not a hard fail). */
    private static void verifySpawn(ServerLevel level, Map<ForceKind, Integer> expected) {
        Map<ForceKind, Integer> seen = new EnumMap<>(ForceKind.class);
        int taggedPlayers = 0;
        for (Entity entity : level.getAllEntities()) {
            if (!entity.getPersistentData().getBoolean(InvasionTags.SPAWN)) continue;
            if (entity instanceof ServerPlayer) {
                taggedPlayers++;
                continue;
            }
            if (!entity.getPersistentData().getBoolean(InvasionTags.AI)) continue;
            ForceKind kind = CaptureOrderSupport.kindOf(entity);
            boolean counts = kind == ForceKind.INFANTRY
                    ? entity instanceof AbstractUnit && !entity.isPassenger()
                    : entity instanceof VehicleEntity;
            if (counts) seen.merge(kind, 1, Integer::sum);
        }
        boolean ok = taggedPlayers == 0;
        for (ForceKind kind : ForceKind.values()) {
            ok &= seen.getOrDefault(kind, 0).equals(expected.getOrDefault(kind, 0));
        }
        SewvDiag.invasion("verifySpawn {} seen={} expected={} taggedPlayers={}",
                ok ? "PASS" : "FAIL", seen, expected, taggedPlayers);
    }

    /** Despawn invasion-tagged entities, clear session-pinned respawns, reset nearby zone progress. */
    public static void teardown(ServerLevel level) {
        CaptureOrderSupport.clearAll(level);
        List<Entity> doomed = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity.getPersistentData().getBoolean(InvasionTags.SPAWN)) {
                doomed.add(entity);
            }
        }
        for (Entity entity : doomed) {
            entity.discard();
        }
        for (ServerPlayer player : level.players()) {
            clearRespawn(player);
        }
        for (CapturableBlockEntity zone : findCapturables(level)) {
            zone.clearCaptureProgress();
            zone.setOwnedTeam("");
        }
        CAPTURABLES.remove(level.dimension());
        SewvDiag.invasion("teardown removed={} dim={}", doomed.size(), level.dimension().location());
    }

    /** Re-assert pinned respawns and top up missing AI forces while the session is live. */
    public static void maintain(ServerLevel level) {
        discardAiWrecks(level);
        discardOrphanAiCrew(level);
        reassertRespawns(level);
        topUpAiForces(level);
    }

    /**
     * SBW keeps destroyed hulls as {@code isWreck()} entities that are still {@code isAlive()}.
     * Counting those as live fleet members permanently blocks top-up — discard them.
     */
    private static void discardAiWrecks(ServerLevel level) {
        List<Entity> doomed = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof VehicleEntity hull)) continue;
            if (!entity.getPersistentData().getBoolean(InvasionTags.SPAWN)) continue;
            if (!entity.getPersistentData().getBoolean(InvasionTags.AI)) continue;
            if (!hull.isWreck()) continue;
            doomed.add(entity);
        }
        for (Entity entity : doomed) {
            entity.discard();
        }
        if (!doomed.isEmpty()) {
            SewvDiag.invasion("discardAiWrecks n={}", doomed.size());
        }
    }

    /**
     * AI crew ejected from a destroyed hull keep SPAWN — drop them so fleets don't litter infantry.
     * Fielded infantry ({@link ForceKind#INFANTRY}) is exempt: being on foot is its whole job.
     * A helicopter's riders are on foot because the helicopter rappelled them into the fight, which
     * is its normal behaviour, so they are kept as infantry ({@link #dismountedAircrew}) rather
     * than deleted.
     */
    private static void discardOrphanAiCrew(ServerLevel level) {
        List<Entity> doomed = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (!entity.getPersistentData().getBoolean(InvasionTags.SPAWN)) continue;
            if (!entity.getPersistentData().getBoolean(InvasionTags.AI)) continue;
            if (entity instanceof VehicleEntity) continue;
            if (entity.isPassenger()) continue;
            ForceKind kind = CaptureOrderSupport.kindOf(entity);
            if (kind == ForceKind.INFANTRY) continue;
            if (kind == ForceKind.ROTOR && entity instanceof AbstractUnit unit) {
                dismountedAircrew(unit);
                continue;
            }
            doomed.add(entity);
        }
        for (Entity entity : doomed) {
            entity.discard();
        }
    }

    /** Re-assert pinned respawns while the session is live (beds must not steal them). */
    public static void reassertRespawns(ServerLevel level) {
        BlockPos worldSpawn = worldSpawnPos(level);
        for (TeamBaseBlockEntity base : findTeamBases(level)) {
            if (!base.isPlayerOwned() || base.getAssignedTeam().isEmpty()) continue;
            BlockPos respawnPos = base.getBlockPos().above();
            for (ServerPlayer player : playersOnTeam(level, base.getAssignedTeam())) {
                if (InvasionSession.isPlayerWaitingField(level, player.getUUID())) {
                    pinRespawn(player, level, worldSpawn);
                } else {
                    pinRespawn(player, level, respawnPos);
                }
            }
        }
    }

    /**
     * Keep each base at its configured live count of every {@link ForceKind}: non-wreck hulls per
     * vehicle kind, living riflemen for infantry (whose target was rolled at match start). Counts
     * come from one entity pass for all bases. Each replacement is a fresh random pool pick.
     * Shortfalls wait that base's {@code spawnDelaySeconds} before spawning (no world-spawn trip).
     */
    public static void topUpAiForces(ServerLevel level) {
        Map<Long, int[]> live = countLiveAi(level);
        for (TeamBaseBlockEntity base : findTeamBases(level)) {
            if (base.getAssignedTeam().isEmpty()) continue;
            int[] have = live.getOrDefault(base.getBlockPos().asLong(), new int[ForceKind.values().length]);
            int[] want = new int[ForceKind.values().length];
            boolean shortfall = false;
            for (ForceKind kind : ForceKind.values()) {
                want[kind.ordinal()] = kind.isVehicle()
                        ? base.getForce().aiCount(kind)
                        : InvasionSession.infantryTarget(level, base);
                shortfall |= have[kind.ordinal()] < want[kind.ordinal()];
            }
            if (!InvasionSession.mayTopUpAi(level, base, shortfall)) continue;

            boolean stillShort = false;
            for (ForceKind kind : ForceKind.values()) {
                int missing = want[kind.ordinal()] - have[kind.ordinal()];
                if (missing <= 0) continue;
                int added = kind.isVehicle()
                        ? spawnAiVehicles(level, base, kind, missing)
                        : spawnAiInfantry(level, base, missing);
                if (added > 0) {
                    beginCaptureOrdersNear(level, base);
                    SewvDiag.invasion("aiTopUp base={} kind={} added={} now={}/{}",
                            base.getBlockPos(), kind, added, have[kind.ordinal()] + added, want[kind.ordinal()]);
                } else {
                    SewvDiag.invasion("aiTopUpFail base={} kind={} have={}/{} (spawn returned 0)",
                            base.getBlockPos(), kind, have[kind.ordinal()], want[kind.ordinal()]);
                }
                stillShort |= added < missing;
            }
            InvasionSession.noteAiTopUpAttempt(level, base, stillShort);
        }
    }

    /** Base pos → live AI count per {@link ForceKind} ordinal. */
    private static Map<Long, int[]> countLiveAi(ServerLevel level) {
        Map<Long, int[]> out = new HashMap<>();
        for (Entity entity : level.getAllEntities()) {
            var data = entity.getPersistentData();
            if (!data.getBoolean(InvasionTags.SPAWN) || !data.getBoolean(InvasionTags.AI)) continue;
            if (!entity.isAlive()) continue;
            ForceKind kind = CaptureOrderSupport.kindOf(entity);
            if (kind == ForceKind.INFANTRY) {
                if (!(entity instanceof AbstractUnit)) continue;
            } else if (!(entity instanceof VehicleEntity hull) || hull.isWreck()) {
                continue;
            }
            out.computeIfAbsent(data.getLong(InvasionTags.BASE), k -> new int[ForceKind.values().length])[kind.ordinal()]++;
        }
        return out;
    }

    /**
     * A player of a player-owned base died mid-match: once vanilla has placed them (deferred one
     * tick), either wait out the base's spawn delay at world spawn or go straight back to the base
     * and get a deployment offer. Called from {@link InvasionSession} (this class is not on the
     * Forge bus).
     */
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        if (!InvasionSession.hasFielded(level)) return;
        if (event.isEndConquered()) return;

        TeamBaseBlockEntity base = playerOwnedBaseFor(level, player);
        if (base == null) return;

        int delaySec = base.getSpawnDelaySeconds();
        // Defer: riding during the respawn event can fail before the player is fully placed.
        level.getServer().execute(() -> {
            if (!InvasionSession.hasFielded(level) || !player.isAlive() || player.hasDisconnected()) return;
            if (player.getVehicle() instanceof VehicleEntity) return; // already mounted

            if (delaySec <= 0) {
                InvasionDeploy.offer(level, base, player);
                pinRespawn(player, level, base.getBlockPos().above());
                return;
            }

            BlockPos at = worldSpawnPos(level);
            teleportToWorldSpawn(player, level, at);
            InvasionSession.schedulePlayerField(level, player, delaySec);
            player.displayClientMessage(Component.translatable(
                    "message.tacz_sewv.invasion.spawn_delay", delaySec), false);
            SewvDiag.invasion("playerRespawnDelay player={} wait={}s at world spawn",
                    player.getGameProfile().getName(), delaySec);
        });
    }

    /** End of a mid-match (or reconnect) spawn-delay: back to the base with a deployment offer. */
    public static void fieldPlayerAfterDelay(ServerLevel level, ServerPlayer player) {
        TeamBaseBlockEntity base = playerOwnedBaseFor(level, player);
        if (base == null) {
            SewvDiag.invasion("fieldPlayerAfterDelay skip player={} — no base",
                    player.getGameProfile().getName());
            return;
        }
        pinRespawn(player, level, base.getBlockPos().above());
        if (player.getVehicle() instanceof VehicleEntity) return;
        player.displayClientMessage(Component.translatable("message.tacz_sewv.invasion.spawn_go"), false);
        InvasionDeploy.offer(level, base, player);
    }

    public static BlockPos worldSpawnPos(ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ());
        return new BlockPos(spawn.getX(), y, spawn.getZ());
    }

    public static void teleportToWorldSpawn(ServerPlayer player, ServerLevel level, BlockPos at) {
        player.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        pinRespawn(player, level, at);
        SewvDiag.invasion("teleportWorldSpawn player={} → {}",
                player.getGameProfile().getName(), at);
    }

    /**
     * Re-tag a helicopter rider now on the ground as infantry. It drops {@link InvasionTags#AI}, so
     * it neither counts toward the base's infantry headcount nor is swept as an orphan again, and
     * it only contests: a helicopter cannot take a point, and neither can what it carries in.
     */
    private static void dismountedAircrew(AbstractUnit unit) {
        var data = unit.getPersistentData();
        data.remove(InvasionTags.AI);
        data.putByte(InvasionTags.KIND, (byte) ForceKind.INFANTRY.ordinal());
        data.putBoolean(InvasionTags.CAPTURES, false);
        CaptureOrderSupport.beginUnit(unit);
        SewvDiag.invasion("aircrewDismounted unit={} team={}", unit.getId(), data.getString(InvasionTags.TEAM));
    }

    /** PMC owner of everything this base fields (null for RU/US, or a PMC base with no owner). */
    @Nullable
    private static UUID pmcOwner(ServerLevel level, TeamBaseBlockEntity base) {
        if (base.getCrewFaction() != TankFaction.PMC) return null;
        return PmcOwnerSupport.resolveSpawnOwnerUuid(level, base.getPmcOwnerKind(), base.getPmcOwnerValue());
    }

    private static boolean pmcTeamOwned(TeamBaseBlockEntity base) {
        return base.getCrewFaction() == TankFaction.PMC && base.getPmcOwnerKind() == PmcOwnerKind.TEAM;
    }

    /**
     * {@code kind}'s pool minus ids whose vehicle data puts them in another class. A tank left in
     * the helicopter pool would otherwise be fielded as aircraft — tagged never to capture — so it
     * is skipped instead (validation warns about it).
     */
    static List<String> fittingPool(TeamBaseBlockEntity base, ForceKind kind) {
        List<String> out = new ArrayList<>();
        for (String id : base.getForce().pool(kind)) {
            if (VehicleClassify.sbwIdFits(kind.poolCategory(), id)) out.add(id);
        }
        return out;
    }

    private static int spawnAiVehicles(ServerLevel level, TeamBaseBlockEntity base, ForceKind kind, int count) {
        if (count <= 0) return 0;
        List<String> pool = fittingPool(base, kind);
        if (pool.isEmpty()) return 0;
        TankFaction faction = base.getCrewFaction();
        UUID ownerId = pmcOwner(level, base);
        float planeYaw = kind == ForceKind.PLANE ? yawTowardFront(level, base) : 0f;

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            String vehicleId = pickRandom(pool, level);
            BlockPos at = offsetInRadius(level, base.getBlockPos(), base.getRadiusInBlocks(),
                    spawned + (int) (level.getGameTime() & 0x7fff));
            // A helicopter goes through the ground path: it spawns on the pad and is ordered to take off.
            VehicleEntity hull = kind == ForceKind.PLANE
                    ? TankSpawner.spawnAirbornePlaneFromPool(level, at, faction, ownerId, vehicleId, pool, planeYaw)
                    : TankSpawner.spawnGroundWithCrewFromPool(level, at, faction, ownerId, vehicleId, pool);
            if (hull == null) {
                SewvDiag.invasion("spawnFail AI base={} kind={} id={}", base.getBlockPos(), kind, vehicleId);
                continue;
            }
            tagHullAndCrew(hull, base, true, kind);
            if (pmcTeamOwned(base)) stampPmcOwnerTeam(hull, base.getPmcOwnerValue());
            beginCaptureOrdersOnCrew(hull);
            spawned++;
        }
        return spawned;
    }

    /** Riflemen around the base, stamped into squads (RU/US) and handed the capture order. */
    private static int spawnAiInfantry(ServerLevel level, TeamBaseBlockEntity base, int count) {
        if (count <= 0) return 0;
        TankFaction faction = base.getCrewFaction();
        UUID ownerId = pmcOwner(level, base);
        boolean captures = base.getForce().infantry().canCapture;
        int scatter = Math.max(2, base.getRadiusInBlocks() / 2);
        List<AbstractUnit> spawned = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            AbstractUnit unit = InfantrySpawner.spawn(level, base.getBlockPos(), faction, ownerId, scatter);
            tag(unit, base, true, ForceKind.INFANTRY, captures);
            if (pmcTeamOwned(base)) {
                PmcOwnerSupport.applyOwnerTeamTag(unit, PmcOwnerKind.TEAM, base.getPmcOwnerValue());
            }
            spawned.add(unit);
        }
        InfantrySquads.stamp(spawned);
        for (AbstractUnit unit : spawned) CaptureOrderSupport.beginUnit(unit);
        return spawned.size();
    }

    /** Yaw from the base toward the point its team is pushing for — so a plane spawns facing the fight. */
    static float yawTowardFront(ServerLevel level, TeamBaseBlockEntity base) {
        BlockPos from = base.getBlockPos();
        BlockPos to = CaptureOrderSupport.frontObjective(level, base.getAssignedTeam(), from);
        if (to == null) return level.random.nextFloat() * 360f;
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
    }

    private static void stampPmcOwnerTeam(VehicleEntity hull, String ownerTeam) {
        if (ownerTeam == null || ownerTeam.isEmpty()) return;
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof ServerPlayer) continue;
            PmcOwnerSupport.applyOwnerTeamTag(passenger, PmcOwnerKind.TEAM, ownerTeam);
        }
    }

    static void beginCaptureOrdersOnCrew(VehicleEntity hull) {
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof AbstractUnit unit) {
                CaptureOrderSupport.beginUnit(unit);
            }
        }
    }

    /** {@code beginUnit} is idempotent on a live order, so re-running it after a top-up is safe. */
    private static void beginCaptureOrdersNear(ServerLevel level, TeamBaseBlockEntity base) {
        long key = base.getBlockPos().asLong();
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof AbstractUnit unit)) continue;
            if (entity.getPersistentData().getLong(InvasionTags.BASE) != key) continue;
            if (unit instanceof ICaptureOrder order && order.sewv$hasCaptureOrder()) continue;
            CaptureOrderSupport.beginUnit(unit);
        }
    }

    static String pickRandom(List<String> pool, ServerLevel level) {
        return pool.get(level.random.nextInt(pool.size()));
    }

    /** Tag a hull and every non-player rider; ground crew capture, aircrew never do. */
    public static void tagHullAndCrew(VehicleEntity hull, TeamBaseBlockEntity base, boolean aiFleet, ForceKind kind) {
        boolean captures = !kind.isAircraft();
        tag(hull, base, aiFleet, kind, captures);
        for (Entity passenger : hull.getPassengers()) {
            // Never tag players — teardown discards SPAWN entities and would delete the rider.
            if (passenger instanceof ServerPlayer) continue;
            tag(passenger, base, aiFleet, kind, captures);
        }
    }

    private static void tag(Entity entity, TeamBaseBlockEntity base, boolean aiFleet, ForceKind kind,
                            boolean captures) {
        var data = entity.getPersistentData();
        data.putBoolean(InvasionTags.SPAWN, true);
        data.putString(InvasionTags.TEAM, base.getAssignedTeam());
        data.putLong(InvasionTags.BASE, base.getBlockPos().asLong());
        data.putByte(InvasionTags.KIND, (byte) kind.ordinal());
        data.putBoolean(InvasionTags.CAPTURES, captures);
        if (aiFleet) {
            data.putBoolean(InvasionTags.AI, true);
        }
        InvasionHostility.stampEnemies(entity, base.getEnemyTeams());
    }

    static TeamBaseBlockEntity playerOwnedBaseFor(ServerLevel level, ServerPlayer player) {
        PlayerTeam team = level.getScoreboard().getPlayersTeam(player.getScoreboardName());
        if (team == null) return null;
        String name = team.getName();
        for (TeamBaseBlockEntity base : findTeamBases(level)) {
            if (!base.isPlayerOwned()) continue;
            if (name.equals(base.getAssignedTeam())) return base;
        }
        return null;
    }

    public static void pinRespawn(ServerPlayer player, ServerLevel level, BlockPos pos) {
        player.setRespawnPosition(level.dimension(), pos, player.getYRot(), true, false);
    }

    private static void clearRespawn(ServerPlayer player) {
        player.setRespawnPosition(player.level().dimension(), null, 0f, false, false);
    }

    private static List<ServerPlayer> playersOnTeam(ServerLevel level, String teamName) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            PlayerTeam team = level.getScoreboard().getPlayersTeam(player.getScoreboardName());
            if (team != null && team.getName().equals(teamName)) {
                out.add(player);
            }
        }
        return out;
    }

    static BlockPos offsetInRadius(ServerLevel level, BlockPos origin, int radius, int salt) {
        int r = Math.max(2, radius);
        int span = r * 2 + 1;
        int dx = Math.floorMod(salt * 7 + 3, span) - r;
        int dz = Math.floorMod(salt * 13 + 5, span) - r;
        return new BlockPos(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
    }

    /**
     * Loaded capturable BEs near players (or world spawn) — used by spawn + capture-order pathing.
     * Reused for {@link #CAPTURABLE_CACHE_TICKS}: the AI re-picks objectives far more often than
     * zones are placed or broken, and each fresh scan walks every chunk around every player.
     */
    public static List<CapturableBlockEntity> findLoadedCapturables(ServerLevel level) {
        long now = level.getGameTime();
        CachedScan cached = CAPTURABLES.get(level.dimension());
        if (cached != null && now - cached.gameTime() >= 0 && now - cached.gameTime() < CAPTURABLE_CACHE_TICKS) {
            List<CapturableBlockEntity> live = new ArrayList<>(cached.zones().size());
            for (CapturableBlockEntity zone : cached.zones()) {
                if (!zone.isRemoved()) live.add(zone);
            }
            return live;
        }
        List<CapturableBlockEntity> fresh = findCapturables(level);
        CAPTURABLES.put(level.dimension(), new CachedScan(now, List.copyOf(fresh)));
        return fresh;
    }

    public static List<TeamBaseBlockEntity> findTeamBases(ServerLevel level) {
        List<TeamBaseBlockEntity> found = new ArrayList<>();
        for (CapturableBlockEntity zone : findCapturables(level)) {
            if (zone instanceof TeamBaseBlockEntity base) found.add(base);
        }
        return found;
    }

    private static List<CapturableBlockEntity> findCapturables(ServerLevel level) {
        List<CapturableBlockEntity> found = new ArrayList<>();
        if (level.players().isEmpty()) {
            scanAround(level, level.getSharedSpawnPos(), BASE_SCAN_CHUNKS, found);
            return found;
        }
        for (ServerPlayer player : level.players()) {
            scanAround(level, player.blockPosition(), BASE_SCAN_CHUNKS, found);
        }
        return found;
    }

    private static void scanAround(ServerLevel level, BlockPos origin, int chunkRadius,
                                   List<CapturableBlockEntity> found) {
        int cx = origin.getX() >> 4;
        int cz = origin.getZ() >> 4;
        for (int x = cx - chunkRadius; x <= cx + chunkRadius; x++) {
            for (int z = cz - chunkRadius; z <= cz + chunkRadius; z++) {
                if (!level.hasChunk(x, z)) continue;
                LevelChunk chunk = level.getChunk(x, z);
                for (var be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof CapturableBlockEntity zone)) continue;
                    boolean dup = false;
                    for (CapturableBlockEntity existing : found) {
                        if (existing.getBlockPos().equals(zone.getBlockPos())) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) found.add(zone);
                }
            }
        }
    }

    public record Result(int bases, int aiVehicles, int aiInfantry, int playersDeploying, int respawnsPinned) {}
}
