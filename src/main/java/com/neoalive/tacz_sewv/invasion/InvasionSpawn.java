package com.neoalive.tacz_sewv.invasion;

import java.util.ArrayList;
import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.spawn.TankSpawner;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * Stage E: spawn vehicles/crews from placed {@link TeamBaseBlockEntity}s and pin player respawns.
 * Mid-match: player death → new pool hull; AI fleet topped up to {@code aiVehicleCount}.
 * Full start validation stays Stage G.
 */
public final class InvasionSpawn {

    /** Chunk radius around each online player searched for team_base BEs on start. */
    private static final int BASE_SCAN_CHUNKS = 24;

    private InvasionSpawn() {}

    /**
     * Spawns for every loaded team_base near players, pins participating players' respawns,
     * and marks each base's {@code ownedTeam} as its {@code assignedTeam}.
     *
     * @return summary counts for chat feedback
     */
    public static Result spawnAll(ServerLevel level) {
        List<TeamBaseBlockEntity> bases = findTeamBases(level);
        int aiVehicles = 0;
        int playerVehicles = 0;
        int respawns = 0;

        for (TeamBaseBlockEntity base : bases) {
            String team = base.getAssignedTeam();
            if (team.isEmpty()) {
                SewvDiag.invasion("spawnSkip base={} — no assignedTeam", base.getBlockPos());
                continue;
            }
            if (base.getVehiclePool().isEmpty()) {
                SewvDiag.invasion("spawnSkip base={} team={} — empty vehicle pool",
                        base.getBlockPos(), team);
                continue;
            }

            // Implicit holder for capture rules / billboard.
            if (base.getOwnedTeam().isEmpty()) {
                base.setOwnedTeam(team);
            }

            BlockPos respawnPos = base.getBlockPos().above();
            if (base.isPlayerOwned()) {
                List<ServerPlayer> members = playersOnTeam(level, team);
                for (ServerPlayer player : members) {
                    VehicleEntity hull = spawnForPlayer(level, base, player);
                    if (hull != null) playerVehicles++;
                    pinRespawn(player, level, respawnPos);
                    respawns++;
                }
                if (members.isEmpty()) {
                    SewvDiag.invasion("spawnWarn base={} team={} — player-owned, no online members",
                            base.getBlockPos(), team);
                }
                // Extra AI fleet on top of player hulls (0 = disabled).
                if (base.getAiVehicleCount() > 0) {
                    aiVehicles += spawnForAiBase(level, base, base.getAiVehicleCount());
                }
            } else {
                aiVehicles += spawnForAiBase(level, base, base.getAiVehicleCount());
            }
        }

        SewvDiag.invasion("spawnDone bases={} aiVehicles={} playerVehicles={} respawnsPinned={}",
                bases.size(), aiVehicles, playerVehicles, respawns);
        CaptureOrderSupport.beginAll(level);
        verifySpawn(level, aiVehicles, playerVehicles);
        return new Result(bases.size(), aiVehicles, playerVehicles, respawns);
    }

    /** Log-backed post-spawn assertions for Stage E playtest (not a hard fail). */
    private static void verifySpawn(ServerLevel level, int expectAiVehicles, int expectPlayerVehicles) {
        int taggedHulls = 0;
        int taggedCrew = 0;
        int taggedPlayers = 0;
        int playerInSeat0 = 0;
        int aiCrewed = 0;
        for (Entity entity : level.getAllEntities()) {
            if (!entity.getPersistentData().getBoolean(InvasionTags.SPAWN)) continue;
            if (entity instanceof ServerPlayer) {
                taggedPlayers++;
                continue;
            }
            if (entity instanceof VehicleEntity hull) {
                taggedHulls++;
                Entity driver = hull.getFirstPassenger();
                if (driver instanceof ServerPlayer) {
                    playerInSeat0++;
                } else if (driver != null) {
                    aiCrewed++;
                }
            } else {
                taggedCrew++;
            }
        }
        boolean ok = taggedPlayers == 0
                && taggedHulls == expectAiVehicles + expectPlayerVehicles
                && playerInSeat0 == expectPlayerVehicles
                && aiCrewed == expectAiVehicles;
        SewvDiag.invasion(
                "verifySpawn {} taggedHulls={} taggedCrew={} taggedPlayers={} playerSeat0={} aiCrewed={} expectAi={} expectPlayer={}",
                ok ? "PASS" : "FAIL",
                taggedHulls, taggedCrew, taggedPlayers, playerInSeat0, aiCrewed,
                expectAiVehicles, expectPlayerVehicles);
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
        SewvDiag.invasion("teardown removed={} dim={}", doomed.size(), level.dimension().location());
    }

    /** Re-assert pinned respawns and top up missing AI / on-foot players while the session is live. */
    public static void maintain(ServerLevel level) {
        discardAiWrecks(level);
        discardOrphanAiCrew(level);
        reassertRespawns(level);
        topUpAiFleets(level);
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

    /** AI crew ejected from a destroyed hull keep SPAWN — drop them so fleets don't litter infantry. */
    private static void discardOrphanAiCrew(ServerLevel level) {
        List<Entity> doomed = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (!entity.getPersistentData().getBoolean(InvasionTags.SPAWN)) continue;
            if (!entity.getPersistentData().getBoolean(InvasionTags.AI)) continue;
            if (entity instanceof VehicleEntity) continue;
            if (entity.isPassenger()) continue;
            doomed.add(entity);
        }
        for (Entity entity : doomed) {
            entity.discard();
        }
    }

    /** Re-assert pinned respawns while the session is live (beds must not steal them). */
    public static void reassertRespawns(ServerLevel level) {
        for (TeamBaseBlockEntity base : findTeamBases(level)) {
            if (!base.isPlayerOwned() || base.getAssignedTeam().isEmpty()) continue;
            BlockPos respawnPos = base.getBlockPos().above();
            for (ServerPlayer player : playersOnTeam(level, base.getAssignedTeam())) {
                pinRespawn(player, level, respawnPos);
            }
        }
    }

    /**
     * Keep each base that fields AI at {@link TeamBaseBlockEntity#getAiVehicleCount()} live
     * non-wreck SPAWN hulls (player-owned bases too — count is additive to player tanks; 0 = off).
     * Each replacement is a fresh random pick from that base's pool.
     */
    public static void topUpAiFleets(ServerLevel level) {
        for (TeamBaseBlockEntity base : findTeamBases(level)) {
            if (base.getAssignedTeam().isEmpty()) continue;
            if (base.getVehiclePool().isEmpty()) continue;
            int want = base.getAiVehicleCount();
            if (want <= 0) continue;
            int have = countAiHulls(level, base.getBlockPos());
            if (have >= want) continue;
            int added = spawnForAiBase(level, base, want - have);
            if (added > 0) {
                SewvDiag.invasion("aiTopUp base={} added={} now={}/{}",
                        base.getBlockPos(), added, have + added, want);
            } else if (have < want) {
                SewvDiag.invasion("aiTopUpFail base={} have={}/{} (spawn returned 0)",
                        base.getBlockPos(), have, want);
            }
        }
    }

    /**
     * After a participating player respawns during an active session, put them back in a
     * pool-picked hull at their team_base (deferred one tick so vanilla respawn settles).
     * Called from {@link InvasionSession} (this class is not on the Forge bus).
     */
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        if (!InvasionSession.isActive(level)) return;
        if (event.isEndConquered()) return;

        TeamBaseBlockEntity base = playerOwnedBaseFor(level, player);
        if (base == null || base.getVehiclePool().isEmpty()) return;

        // Defer: riding during the respawn event can fail before the player is fully placed.
        level.getServer().execute(() -> {
            if (!InvasionSession.isActive(level) || !player.isAlive() || player.hasDisconnected()) return;
            if (player.getVehicle() instanceof VehicleEntity) return; // already mounted
            VehicleEntity hull = spawnForPlayer(level, base, player);
            SewvDiag.invasion("playerRespawnTank player={} ok={} base={}",
                    player.getGameProfile().getName(), hull != null, base.getBlockPos());
        });
    }

    private static int spawnForAiBase(ServerLevel level, TeamBaseBlockEntity base, int count) {
        String team = base.getAssignedTeam();
        TankFaction faction = base.getCrewFaction();
        List<String> pool = base.getVehiclePool();
        if (pool.isEmpty() || count <= 0) return 0;

        java.util.UUID ownerId = null;
        PmcOwnerKind ownerKind = PmcOwnerKind.NONE;
        String ownerValue = "";
        if (faction == TankFaction.PMC) {
            ownerKind = base.getPmcOwnerKind();
            ownerValue = base.getPmcOwnerValue();
            ownerId = PmcOwnerSupport.resolveSpawnOwnerUuid(level, ownerKind, ownerValue);
        }

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            String vehicleId = pickRandom(pool, level);
            BlockPos at = offsetInRadius(level, base.getBlockPos(), base.getRadiusInBlocks(),
                    spawned + (int) (level.getGameTime() & 0x7fff));
            VehicleEntity hull = TankSpawner.spawnTankWithCrewFromPool(
                    level, at, faction, ownerId, vehicleId, pool);
            if (hull == null) {
                SewvDiag.invasion("spawnFail AI base={} id={}", base.getBlockPos(), vehicleId);
                continue;
            }
            tagHullAndCrew(hull, team, base.getBlockPos(), true, base.getEnemyTeams());
            if (faction == TankFaction.PMC && ownerKind == PmcOwnerKind.TEAM) {
                stampPmcOwnerTeam(hull, ownerValue);
            }
            beginCaptureOrdersOnCrew(hull);
            spawned++;
        }
        return spawned;
    }

    private static void stampPmcOwnerTeam(VehicleEntity hull, String ownerTeam) {
        if (ownerTeam == null || ownerTeam.isEmpty()) return;
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof ServerPlayer) continue;
            PmcOwnerSupport.applyOwnerTeamTag(passenger, PmcOwnerKind.TEAM, ownerTeam);
        }
    }

    private static void beginCaptureOrdersOnCrew(VehicleEntity hull) {
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof AbstractUnit unit) {
                CaptureOrderSupport.beginUnit(unit);
            }
        }
    }

    private static VehicleEntity spawnForPlayer(ServerLevel level, TeamBaseBlockEntity base,
                                                ServerPlayer player) {
        String team = base.getAssignedTeam();
        List<String> pool = base.getVehiclePool();
        if (pool.isEmpty()) return null;
        String vehicleId = pickRandom(pool, level);
        BlockPos at = offsetInRadius(level, base.getBlockPos(), base.getRadiusInBlocks(),
                player.getId() + (int) (level.getGameTime() & 0xff));
        VehicleEntity hull = TankSpawner.spawnPlayerDrivenWithOptionalCrew(
                level, at, player, vehicleId, pool, base.isSpawnPlayerOwnedTanksWithNpc());
        if (hull == null) {
            SewvDiag.invasion("spawnFail player={} base={} id={}",
                    player.getGameProfile().getName(), base.getBlockPos(), vehicleId);
            return null;
        }
        tagHullAndCrew(hull, team, base.getBlockPos(), false, base.getEnemyTeams());
        beginCaptureOrdersOnCrew(hull);
        return hull;
    }

    private static String pickRandom(List<String> pool, ServerLevel level) {
        return pool.get(level.random.nextInt(pool.size()));
    }

    public static void tagHullAndCrew(VehicleEntity hull, String team, BlockPos basePos, boolean aiFleet,
                                      List<String> enemyTeams) {
        tag(hull, team, basePos, aiFleet, enemyTeams);
        for (Entity passenger : hull.getPassengers()) {
            // Never tag players — teardown discards SPAWN entities and would delete the rider.
            if (passenger instanceof ServerPlayer) continue;
            tag(passenger, team, basePos, aiFleet, enemyTeams);
        }
    }

    /** @deprecated prefer {@link #tagHullAndCrew(VehicleEntity, String, BlockPos, boolean, List)} */
    public static void tagHullAndCrew(VehicleEntity hull, String team, BlockPos basePos, boolean aiFleet) {
        tagHullAndCrew(hull, team, basePos, aiFleet, List.of());
    }

    private static void tag(Entity entity, String team, BlockPos basePos, boolean aiFleet,
                            List<String> enemyTeams) {
        entity.getPersistentData().putBoolean(InvasionTags.SPAWN, true);
        entity.getPersistentData().putString(InvasionTags.TEAM, team);
        entity.getPersistentData().putLong(InvasionTags.BASE, basePos.asLong());
        if (aiFleet) {
            entity.getPersistentData().putBoolean(InvasionTags.AI, true);
        }
        InvasionHostility.stampEnemies(entity, enemyTeams);
    }

    private static int countAiHulls(ServerLevel level, BlockPos basePos) {
        long key = basePos.asLong();
        int n = 0;
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof VehicleEntity hull)) continue;
            if (!entity.getPersistentData().getBoolean(InvasionTags.SPAWN)) continue;
            if (!entity.getPersistentData().getBoolean(InvasionTags.AI)) continue;
            if (entity.getPersistentData().getLong(InvasionTags.BASE) != key) continue;
            if (!entity.isAlive() || hull.isWreck()) continue;
            n++;
        }
        return n;
    }

    private static TeamBaseBlockEntity playerOwnedBaseFor(ServerLevel level, ServerPlayer player) {
        PlayerTeam team = level.getScoreboard().getPlayersTeam(player.getScoreboardName());
        if (team == null) return null;
        String name = team.getName();
        for (TeamBaseBlockEntity base : findTeamBases(level)) {
            if (!base.isPlayerOwned()) continue;
            if (name.equals(base.getAssignedTeam())) return base;
        }
        return null;
    }

    private static void pinRespawn(ServerPlayer player, ServerLevel level, BlockPos pos) {
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

    private static BlockPos offsetInRadius(ServerLevel level, BlockPos origin, int radius, int salt) {
        int r = Math.max(2, radius);
        int span = r * 2 + 1;
        int dx = Math.floorMod(salt * 7 + 3, span) - r;
        int dz = Math.floorMod(salt * 13 + 5, span) - r;
        return new BlockPos(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
    }

    /** Loaded capturable BEs near players (or world spawn) — used by spawn + capture-order pathing. */
    public static List<CapturableBlockEntity> findLoadedCapturables(ServerLevel level) {
        return findCapturables(level);
    }

    static List<TeamBaseBlockEntity> findTeamBases(ServerLevel level) {
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

    public record Result(int bases, int aiVehicles, int playerVehicles, int respawnsPinned) {}
}
