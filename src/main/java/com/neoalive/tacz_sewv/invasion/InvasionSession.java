package com.neoalive.tacz_sewv.invasion;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import com.neoalive.tacz_sewv.block.TeamBaseBlockEntity;
import com.neoalive.tacz_sewv.debug.SewvDiag;

/**
 * Per-dimension invasion match state: validate → ticket → (optional delay at world spawn) →
 * teleport → spawn → maintain → teardown.
 * Mid-match deaths reuse the same spawn-delay: players wait at world spawn; AI fleets just wait.
 */
public final class InvasionSession {

    private static final Map<ResourceKey<Level>, InvasionSession> BY_DIM = new ConcurrentHashMap<>();
    private static final int RESPAWN_REASSERT_INTERVAL = 100; // 5s

    private boolean active;
    /** False until vehicles/crews have been fielded (after any spawn delay). */
    private boolean fielded;
    /** Absolute game time when delayed spawn fires; {@link Long#MIN_VALUE} when not waiting. */
    private long spawnAtGameTime = Long.MIN_VALUE;
    private int nextRespawnAssert = Integer.MIN_VALUE;
    /** Player UUID → game time when they may leave world spawn and receive a tank. */
    private final Map<UUID, Long> playerFieldAt = new ConcurrentHashMap<>();
    /** Team-base packed pos → game time when AI top-up may spawn replacements. */
    private final Map<Long, Long> aiTopUpAt = new ConcurrentHashMap<>();
    /** Team-base packed pos → infantry headcount for this match (a RANDOM spec is rolled once). */
    private final Map<Long, Integer> infantryTargets = new ConcurrentHashMap<>();
    /** Player UUID → outstanding deployment offer ({@link InvasionDeploy}). */
    private final Map<UUID, InvasionDeploy.Pending> pendingDeploys = new ConcurrentHashMap<>();
    /** Match length from {@code /sewv invasion start <seconds>}; 0 = no limit. */
    private int timeLimitSeconds;
    /**
     * Absolute game time the match runs out, or {@link Long#MIN_VALUE} when unlimited or not yet
     * fielded. The clock starts at fielding, not at the command, so a spawn delay can never eat
     * the match — nobody is on the field while it runs.
     */
    private long endAtGameTime = Long.MIN_VALUE;
    /** Zone packed pos → the team it was last captured FROM (absent: taken while neutral, or never). */
    private final Map<Long, String> takenFrom = new ConcurrentHashMap<>();
    @javax.annotation.Nullable
    private InvasionHud.Layout hudLayout;

    private InvasionSession() {}

    public static InvasionSession of(ServerLevel level) {
        return BY_DIM.computeIfAbsent(level.dimension(), k -> new InvasionSession());
    }

    public static boolean isActive(ServerLevel level) {
        InvasionSession session = BY_DIM.get(level.dimension());
        return session != null && session.active;
    }

    /**
     * Whether a match runs in any dimension. Level-free on purpose: Easy Mode's exemption is read
     * from deep AI paths (spread, cooldowns, turret speed) that have no {@link ServerLevel} to hand.
     * One entry per dimension at most, so the scan is a couple of reads.
     */
    public static boolean anyActive() {
        for (InvasionSession session : BY_DIM.values()) {
            if (session.active) return true;
        }
        return false;
    }

    /** True once AI/player hulls have been spawned (false during the pre-spawn delay). */
    public static boolean hasFielded(ServerLevel level) {
        InvasionSession session = BY_DIM.get(level.dimension());
        return session != null && session.active && session.fielded;
    }

    /** True while a player is serving a mid-match (or start) spawn-delay at world spawn. */
    public static boolean isPlayerWaitingField(ServerLevel level, UUID playerId) {
        InvasionSession session = BY_DIM.get(level.dimension());
        return session != null && session.playerFieldAt.containsKey(playerId);
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @javax.annotation.Nullable
    public InvasionHud.Layout hudLayout() {
        return hudLayout;
    }

    public sealed interface StartResult {
        record Ok(InvasionSpawn.Result spawn, List<String> warnings, int spawnDelaySeconds)
                implements StartResult {}
        record Fail(List<String> errors) implements StartResult {}
    }

    /**
     * Full start: validate → ticket → optional world-spawn wait → teleport teams → spawn.
     */
    public static StartResult start(ServerLevel level, int timeLimitSeconds) {
        InvasionSession session = of(level);
        if (session.active) {
            return new StartResult.Fail(List.of("already_active"));
        }

        InvasionValidate.Report report = InvasionValidate.validate(level);
        if (!report.ok()) {
            SewvDiag.invasion("start FAIL errors={}", report.errors());
            return new StartResult.Fail(List.copyOf(report.errors()));
        }
        for (String warn : report.warnings()) {
            SewvDiag.invasion("start WARN {}", warn);
        }

        InvasionHud.Layout layout = InvasionHud.buildLayout(report.bases(), report.points());
        if (layout == null) {
            return new StartResult.Fail(List.of("need_exactly_two_bases"));
        }

        int delaySec = maxSpawnDelay(report.bases());

        InvasionTickets.ticketAll(level);

        session.active = true;
        session.fielded = false;
        session.nextRespawnAssert = Integer.MIN_VALUE;
        session.playerFieldAt.clear();
        session.aiTopUpAt.clear();
        session.infantryTargets.clear();
        session.pendingDeploys.clear();
        session.takenFrom.clear();
        session.timeLimitSeconds = Math.max(0, timeLimitSeconds);
        session.endAtGameTime = Long.MIN_VALUE;
        session.hudLayout = layout;
        InvasionLayout.get(level).setSessionActive(true);
        // Players' teams are known from here on; PMC targeting reads the overlay this writes.
        InvasionDiplomacy.sync(level, report.bases());

        InvasionHudTracker.push(level);

        if (delaySec <= 0) {
            session.spawnAtGameTime = Long.MIN_VALUE;
            teleportPlayersToBases(level, report.bases());
            InvasionSpawn.Result spawn = InvasionSpawn.spawnAll(level);
            session.fielded = true;
            session.startClock(level);
            SewvDiag.invasion("session ACTIVE dim={} spawn={} hudSlots={}",
                    level.dimension().location(), spawn, layout.slots().size());
            return new StartResult.Ok(spawn, List.copyOf(report.warnings()), 0);
        }

        session.spawnAtGameTime = level.getGameTime() + delaySec * 20L;
        teleportPlayersToWorldSpawn(level, report.bases());
        for (ServerPlayer player : level.players()) {
            if (!playerOnAssignedTeam(level, player, report.bases())) continue;
            session.playerFieldAt.put(player.getUUID(), session.spawnAtGameTime);
            player.displayClientMessage(Component.translatable(
                    "message.tacz_sewv.invasion.spawn_delay", delaySec), false);
        }
        SewvDiag.invasion("session ACTIVE dim={} spawnDelay={}s (waiting at world spawn)",
                level.dimension().location(), delaySec);
        return new StartResult.Ok(new InvasionSpawn.Result(report.bases().size(), 0, 0, 0, 0),
                List.copyOf(report.warnings()), delaySec);
    }

    private static int maxSpawnDelay(List<TeamBaseBlockEntity> bases) {
        int max = 0;
        for (TeamBaseBlockEntity base : bases) {
            max = Math.max(max, base.getSpawnDelaySeconds());
        }
        return max;
    }

    public static void deactivate(ServerLevel level) {
        InvasionSession session = of(level);
        boolean wasActive = session.active || InvasionLayout.get(level).isSessionActive();
        if (!wasActive) return;
        forceEnd(level);
    }

    /**
     * Always tear down invasion spawns/tickets/HUD and clear the persisted session flag —
     * even when memory already thinks the match is idle (orphaned SPAWN NBT after a crash).
     */
    public static void forceEnd(ServerLevel level) {
        InvasionSession session = of(level);
        session.active = false;
        session.fielded = false;
        session.spawnAtGameTime = Long.MIN_VALUE;
        session.playerFieldAt.clear();
        session.aiTopUpAt.clear();
        session.infantryTargets.clear();
        session.pendingDeploys.clear();
        session.takenFrom.clear();
        session.timeLimitSeconds = 0;
        session.endAtGameTime = Long.MIN_VALUE;
        session.hudLayout = null;
        InvasionHudTracker.clear(level);
        InvasionDiplomacy.clear(level);
        InvasionSpawn.teardown(level);
        InvasionTickets.releaseAll(level);
        InvasionLayout.get(level).setSessionActive(false);
        SewvDiag.invasion("session FORCE_END dim={}", level.dimension().location());
    }

    /**
     * After a reload the in-memory session is gone but SavedData may still say active —
     * force-stop cleanup so orphaned spawns/tickets do not linger.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            InvasionLayout layout = InvasionLayout.get(level);
            if (!layout.isSessionActive()) continue;
            SewvDiag.invasion("orphan session on load — force stop dim={}", level.dimension().location());
            InvasionSession session = of(level);
            session.active = true; // so deactivate runs teardown
            deactivate(level);
        }
    }

    /**
     * Sessions are keyed by dimension, and dimension keys repeat across worlds — a match left
     * running when a singleplayer world closed would otherwise read as active in the next one.
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        BY_DIM.clear();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        int tick = event.getServer().getTickCount();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            InvasionSession session = BY_DIM.get(level.dimension());
            if (session == null || !session.active) continue;

            if (!session.fielded && session.spawnAtGameTime != Long.MIN_VALUE
                    && level.getGameTime() >= session.spawnAtGameTime) {
                finishDelayedSpawn(level, session);
            }

            if (!session.fielded) continue;
            if (session.endAtGameTime != Long.MIN_VALUE && level.getGameTime() >= session.endAtGameTime) {
                concludeOnTime(level, session);
                continue;
            }
            tickPlayerFieldDelays(level, session);
            tickPendingDeploys(level, session);
            if (tick < session.nextRespawnAssert) continue;
            session.nextRespawnAssert = tick + RESPAWN_REASSERT_INTERVAL;
            InvasionSpawn.maintain(level);
            // Picks up a player who joined a /team mid-match.
            InvasionDiplomacy.sync(level);
        }
    }

    /**
     * The infantry headcount {@code base} keeps for this match. A RANDOM spec is rolled on first
     * ask and then held, so top-up refills to the same number all match; a new match re-rolls.
     */
    public static int infantryTarget(ServerLevel level, TeamBaseBlockEntity base) {
        return of(level).infantryTargets.computeIfAbsent(base.getBlockPos().asLong(),
                k -> base.getForce().infantry().resolveCount(level.random));
    }

    @javax.annotation.Nullable
    public static InvasionDeploy.Pending pendingDeploy(ServerLevel level, UUID playerId) {
        InvasionSession session = BY_DIM.get(level.dimension());
        return session == null ? null : session.pendingDeploys.get(playerId);
    }

    public static void setPendingDeploy(ServerLevel level, UUID playerId, InvasionDeploy.Pending pending) {
        of(level).pendingDeploys.put(playerId, pending);
    }

    public static void clearPendingDeploy(ServerLevel level, UUID playerId) {
        InvasionSession session = BY_DIM.get(level.dimension());
        if (session != null) session.pendingDeploys.remove(playerId);
    }

    private static void tickPendingDeploys(ServerLevel level, InvasionSession session) {
        if (session.pendingDeploys.isEmpty()) return;
        long now = level.getGameTime();
        for (Map.Entry<UUID, InvasionDeploy.Pending> entry : List.copyOf(session.pendingDeploys.entrySet())) {
            if (now < entry.getValue().deadline()) continue;
            session.pendingDeploys.remove(entry.getKey());
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.level() != level || player.hasDisconnected()) continue;
            InvasionDeploy.expire(level, player, entry.getValue());
        }
    }

    /** Schedule a mid-match player fielding after {@code delaySeconds} at world spawn. */
    public static void schedulePlayerField(ServerLevel level, ServerPlayer player, int delaySeconds) {
        InvasionSession session = of(level);
        long at = level.getGameTime() + Math.max(0, delaySeconds) * 20L;
        session.playerFieldAt.put(player.getUUID(), at);
    }

    public static void clearPlayerField(ServerLevel level, UUID playerId) {
        InvasionSession session = BY_DIM.get(level.dimension());
        if (session != null) session.playerFieldAt.remove(playerId);
    }

    /**
     * AI top-up gate: returns true when a shortfall may spawn now. First shortfall starts the
     * base's spawn-delay clock; clears when the fleet is full again.
     */
    public static boolean mayTopUpAi(ServerLevel level, TeamBaseBlockEntity base, boolean shortfall) {
        InvasionSession session = of(level);
        long key = base.getBlockPos().asLong();
        if (!shortfall) {
            session.aiTopUpAt.remove(key);
            return false;
        }
        int delaySec = base.getSpawnDelaySeconds();
        if (delaySec <= 0) return true;
        long now = level.getGameTime();
        Long ready = session.aiTopUpAt.get(key);
        if (ready == null) {
            session.aiTopUpAt.put(key, now + delaySec * 20L);
            SewvDiag.invasion("aiTopUpDelay base={} wait={}s", base.getBlockPos(), delaySec);
            return false;
        }
        if (now < ready) return false;
        return true;
    }

    /** After a delayed AI top-up attempt, restart the clock if still short. */
    public static void noteAiTopUpAttempt(ServerLevel level, TeamBaseBlockEntity base, boolean stillShort) {
        InvasionSession session = of(level);
        long key = base.getBlockPos().asLong();
        if (!stillShort) {
            session.aiTopUpAt.remove(key);
            return;
        }
        int delaySec = base.getSpawnDelaySeconds();
        if (delaySec <= 0) {
            session.aiTopUpAt.remove(key);
            return;
        }
        session.aiTopUpAt.put(key, level.getGameTime() + delaySec * 20L);
    }

    private static void tickPlayerFieldDelays(ServerLevel level, InvasionSession session) {
        if (session.playerFieldAt.isEmpty()) return;
        long now = level.getGameTime();
        for (Map.Entry<UUID, Long> entry : List.copyOf(session.playerFieldAt.entrySet())) {
            if (now < entry.getValue()) continue;
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.level() != level) continue;
            if (!player.isAlive() || player.hasDisconnected()) continue;
            session.playerFieldAt.remove(entry.getKey());
            InvasionSpawn.fieldPlayerAfterDelay(level, player);
        }
    }

    private static void finishDelayedSpawn(ServerLevel level, InvasionSession session) {
        session.spawnAtGameTime = Long.MIN_VALUE;
        session.playerFieldAt.clear();
        List<TeamBaseBlockEntity> bases = InvasionSpawn.findTeamBases(level);
        teleportPlayersToBases(level, bases);
        InvasionSpawn.Result spawn = InvasionSpawn.spawnAll(level);
        session.fielded = true;
        session.startClock(level);
        InvasionHudTracker.push(level);
        for (ServerPlayer player : level.players()) {
            player.displayClientMessage(Component.translatable(
                    "message.tacz_sewv.invasion.spawn_go"), false);
        }
        SewvDiag.invasion("session FIELDING dim={} spawn={}", level.dimension().location(), spawn);
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        InvasionSpawn.onPlayerRespawn(event);
    }

    private void startClock(ServerLevel level) {
        this.endAtGameTime = this.timeLimitSeconds > 0
                ? level.getGameTime() + this.timeLimitSeconds * 20L
                : Long.MIN_VALUE;
    }

    /** Whole seconds left on the match clock, or -1 when it has no limit (or has not started). */
    public static int secondsLeft(ServerLevel level) {
        InvasionSession session = BY_DIM.get(level.dimension());
        if (session == null || session.endAtGameTime == Long.MIN_VALUE) return -1;
        return (int) Math.max(0, (session.endAtGameTime - level.getGameTime() + 19) / 20);
    }

    /**
     * Record who a zone was taken from. Called by {@link CaptureSupport} with the holder from just
     * before the capture; a zone taken while neutral clears the record, so "taken from the enemy"
     * always describes the latest change of hands.
     */
    public static void noteCapture(ServerLevel level, BlockPos pos, String previousHolder, String newHolder) {
        InvasionSession session = of(level);
        if (previousHolder.isEmpty() || previousHolder.equals(newHolder)) {
            session.takenFrom.remove(pos.asLong());
        } else {
            session.takenFrom.put(pos.asLong(), previousHolder);
        }
    }

    public void onPointCaptured(ServerLevel level, CapturableBlockEntity point, String capturingTeam) {
        SewvDiag.invasion("pointCaptured pos={} team={}", point.getBlockPos(), capturingTeam);
    }

    /** The clock ran out: score the field ({@link MatchOutcome}), announce, end. */
    private static void concludeOnTime(ServerLevel level, InvasionSession session) {
        InvasionHud.Layout layout = session.hudLayout;
        if (layout == null) {
            deactivate(level);
            return;
        }
        String teamA = layout.teamA();
        String teamB = layout.teamB();
        int pointsA = 0;
        int pointsB = 0;
        boolean occupiesA = false;
        boolean occupiesB = false;
        InvasionLayout registry = InvasionLayout.get(level);
        java.util.List<Long> zones = new java.util.ArrayList<>(registry.capturePointPositions());
        zones.addAll(registry.teamBasePositions());
        for (long packed : zones) {
            BlockPos pos = BlockPos.of(packed);
            if (!(level.getBlockEntity(pos) instanceof CapturableBlockEntity zone)) continue;
            String holder = CaptureSupport.holdingTeam(zone);
            if (holder.isEmpty()) continue;
            boolean point = !(zone instanceof TeamBaseBlockEntity);
            if (point && holder.equals(teamA)) pointsA++;
            if (point && holder.equals(teamB)) pointsB++;
            String from = session.takenFrom.getOrDefault(packed, "");
            boolean enemyBase = zone instanceof TeamBaseBlockEntity base && !holder.equals(base.getAssignedTeam());
            boolean takenFromEnemy = enemyBase || (!from.isEmpty() && !from.equals(holder));
            if (takenFromEnemy && holder.equals(teamA)) occupiesA = true;
            if (takenFromEnemy && holder.equals(teamB)) occupiesB = true;
        }
        MatchOutcome.Verdict verdict = MatchOutcome.decide(occupiesA, occupiesB, pointsA, pointsB);
        SewvDiag.invasion("timeUp A={} pts={} occ={} | B={} pts={} occ={} → {}",
                teamA, pointsA, occupiesA, teamB, pointsB, occupiesB, verdict);
        String winner = switch (verdict.result()) {
            case TEAM_A -> teamA;
            case TEAM_B -> teamB;
            case TIE -> "";
        };
        Component reason = Component.translatable("message.tacz_sewv.invasion.result.reason."
                + verdict.reason().name().toLowerCase(java.util.Locale.ROOT), pointsA, pointsB, teamA, teamB);
        announce(level, winner, reason);
        deactivate(level);
    }

    /** Chat line plus a title on every player in the dimension. {@code winner} empty = tie. */
    private static void announce(ServerLevel level, String winner, Component reason) {
        Component title = winner.isEmpty()
                ? Component.translatable("message.tacz_sewv.invasion.result.tie")
                : Component.translatable("message.tacz_sewv.invasion.result.win", winner);
        for (ServerPlayer player : level.players()) {
            player.displayClientMessage(Component.empty().append(title).append(" — ").append(reason), false);
            player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 80, 20));
            player.connection.send(new ClientboundSetTitleTextPacket(title));
            player.connection.send(new ClientboundSetSubtitleTextPacket(reason));
        }
    }

    public void onBaseCaptured(ServerLevel level, TeamBaseBlockEntity base, String capturingTeam) {
        SewvDiag.invasion("baseCaptured pos={} assigned={} endOnCapture={} capturer={}",
                base.getBlockPos(), base.getAssignedTeam(), base.isEndInvasionOnCapture(), capturingTeam);
        if (!base.isEndInvasionOnCapture()) return;

        announce(level, capturingTeam, Component.translatable(
                "message.tacz_sewv.invasion.base_fallen", base.getAssignedTeam(), capturingTeam));
        deactivate(level);
    }

    private static void teleportPlayersToWorldSpawn(ServerLevel level, List<TeamBaseBlockEntity> bases) {
        BlockPos at = InvasionSpawn.worldSpawnPos(level);
        for (ServerPlayer player : level.players()) {
            if (!playerOnAssignedTeam(level, player, bases)) continue;
            InvasionSpawn.teleportToWorldSpawn(player, level, at);
        }
    }

    private static boolean playerOnAssignedTeam(ServerLevel level, ServerPlayer player,
                                                List<TeamBaseBlockEntity> bases) {
        PlayerTeam team = level.getScoreboard().getPlayersTeam(player.getScoreboardName());
        if (team == null) return false;
        String name = team.getName();
        for (TeamBaseBlockEntity base : bases) {
            if (name.equals(base.getAssignedTeam())) return true;
        }
        return false;
    }

    private static void teleportPlayersToBases(ServerLevel level, List<TeamBaseBlockEntity> bases) {
        for (TeamBaseBlockEntity base : bases) {
            String teamName = base.getAssignedTeam();
            if (teamName.isEmpty()) continue;
            BlockPos origin = base.getBlockPos();
            int radius = Math.max(2, base.getRadiusInBlocks());
            int i = 0;
            for (ServerPlayer player : level.players()) {
                PlayerTeam team = level.getScoreboard().getPlayersTeam(player.getScoreboardName());
                if (team == null || !team.getName().equals(teamName)) continue;
                BlockPos at = scatter(level, origin, radius, i++);
                player.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                SewvDiag.invasion("teleport player={} → {}", player.getGameProfile().getName(), at);
            }
        }
    }

    private static BlockPos scatter(ServerLevel level, BlockPos origin, int radius, int salt) {
        int span = radius * 2 + 1;
        int dx = Math.floorMod(salt * 7 + 3, span) - radius;
        int dz = Math.floorMod(salt * 13 + 5, span) - radius;
        int x = origin.getX() + dx;
        int z = origin.getZ() + dz;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }
}
